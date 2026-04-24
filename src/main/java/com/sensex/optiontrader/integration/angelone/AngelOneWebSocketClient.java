package com.sensex.optiontrader.integration.angelone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sensex.optiontrader.config.AngelOneProperties;
import com.sensex.optiontrader.config.AngelOneProperties.InstrumentToken;
import com.sensex.optiontrader.service.InstrumentRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Angel One SmartAPI WebSocket v2 client.
 * <p>
 * Connects with feed-token authentication, subscribes to instruments in Quote mode,
 * and parses the proprietary binary frames into {@link LiveTickData} events.
 * <p>
 * Binary layout matches Angel {@code smartWebSocketV2.py} (int64 prices in paise, /100 → rupees):
 * <pre>
 *   [0]       subscription_mode   uint8   (1=LTP, 2=Quote, 3=SnapQuote, 4=Depth)
 *   [1]       exchange_type       uint8
 *   [2-26]    token               char[25]
 *   [27-34]   sequence_number     int64 LE
 *   [35-42]   exchange_timestamp  int64 LE  (epoch ms)
 *   [43-50]   last_traded_price   int64 LE  (paise → /100 for rupees)
 *   --- Modes 2 & 3 only (Quote / SnapQuote) ---
 *   [51-58]   last_traded_qty     int64 LE
 *   [59-66]   average_traded_price int64 LE (paise → /100)
 *   [67-74]   volume_for_day      int64 LE
 *   [75-82]   total_buy_qty       double LE
 *   [83-90]   total_sell_qty      double LE
 *   [91-98]   open                int64 LE (/100)
 *   ... high, low, close int64 each → wire ends at byte 123 for mode 2
 *   --- Mode 3 extra (SnapQuote total 379 bytes) ---
 *   timestamps, OI, best-5 book, circuits, 52w — see Angel Python parser bytes 123-378
 * </pre>
 */
@Slf4j
@Component
public class AngelOneWebSocketClient {

    /** Mode 1: LTP only (official SmartAPI v2). */
    private static final int WIRE_LTP = 51;
    /** Mode 2: Quote (OHLC + volume + LTP). */
    private static final int WIRE_QUOTE = 123;
    /** Mode 3: Snap quote (includes best-5 and circuits; must consume full packet to stay aligned). */
    private static final int WIRE_SNAP_QUOTE = 379;
    /** Mode 4: 20-depth packet (approx; depth-only subscriptions). */
    private static final int WIRE_DEPTH = 443;
    private static final int HEADER_LEN = 43;
    private static final int TOKEN_FIELD_LEN = 25;

    private final AngelOneProperties props;
    private final AngelOneAuthService authService;
    private final InstrumentRegistry instrumentRegistry;
    private final ObjectMapper objectMapper;
    private final List<Consumer<LiveTickData>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ByteBuffer accumulator = ByteBuffer.allocate(0);

    public AngelOneWebSocketClient(AngelOneProperties props,
                                   AngelOneAuthService authService,
                                   InstrumentRegistry instrumentRegistry,
                                   ObjectMapper objectMapper) {
        this.props = props;
        this.authService = authService;
        this.instrumentRegistry = instrumentRegistry;
        this.objectMapper = objectMapper;
    }

    public void addTickListener(Consumer<LiveTickData> listener) {
        listeners.add(listener);
    }

    public boolean isConnected() {
        WebSocket ws = wsRef.get();
        return ws != null && !ws.isInputClosed() && !ws.isOutputClosed();
    }

    /**
     * Opens the WebSocket, subscribes to the configured instruments, and begins streaming.
     * Blocks until the connection is established or times out (10 s).
     */
    public void connect() {
        if (running.getAndSet(true)) {
            log.warn("WebSocket connect called while already running");
            return;
        }

        String jwt = authService.getJwtToken();
        String feed = authService.getFeedToken().get();
        if (jwt == null || feed == null) {
            running.set(false);
            throw new IllegalStateException("Not authenticated — call login() first");
        }

        CountDownLatch openLatch = new CountDownLatch(1);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            WebSocket ws = client.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + jwt)
                    .header("x-api-key", props.apiKey())
                    .header("x-client-code", props.clientCode())
                    .header("x-feed-token", feed)
                    .buildAsync(URI.create(props.wsUrl()), new WebSocket.Listener() {

                        @Override
                        public void onOpen(WebSocket webSocket) {
                            log.info("Angel One WebSocket connected");
                            webSocket.request(1);
                            openLatch.countDown();
                        }

                        @Override
                        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                            handleBinaryFrame(data);
                            webSocket.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            log.debug("Angel One WS text: {}", data);
                            webSocket.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
                            webSocket.sendPong(message);
                            webSocket.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                            log.warn("Angel One WebSocket closed: {} {}", statusCode, reason);
                            running.set(false);
                            openLatch.countDown();
                            return null;
                        }

                        @Override
                        public void onError(WebSocket webSocket, Throwable error) {
                            log.error("Angel One WebSocket error: {}", error.getMessage());
                            running.set(false);
                            openLatch.countDown();
                        }
                    })
                    .join();

            wsRef.set(ws);

            if (!openLatch.await(10, TimeUnit.SECONDS)) {
                log.error("Angel One WebSocket open timed out");
                running.set(false);
                return;
            }

            subscribe(ws);

        } catch (Exception e) {
            running.set(false);
            throw new IllegalStateException("Angel One WebSocket connection failed", e);
        }
    }

    public void disconnect() {
        running.set(false);
        WebSocket ws = wsRef.getAndSet(null);
        if (ws != null && !ws.isOutputClosed()) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
        log.info("Angel One WebSocket disconnected");
    }

    /**
     * Resubscribes the existing WebSocket to the current active instruments from the DB.
     * Call after switching the active instrument via the API.
     */
    public void resubscribe() {
        WebSocket ws = wsRef.get();
        if (ws == null || ws.isOutputClosed()) {
            log.warn("Cannot resubscribe — WebSocket not connected");
            return;
        }
        unsubscribe(ws);
        subscribe(ws);
    }

    private void unsubscribe(WebSocket ws) {
        List<InstrumentToken> instruments = instrumentRegistry.getActiveInstruments();
        if (instruments.isEmpty()) return;

        Map<Integer, List<String>> byExchange = new LinkedHashMap<>();
        for (InstrumentToken inst : instruments) {
            byExchange.computeIfAbsent(inst.exchangeType(), k -> new ArrayList<>()).add(inst.token());
        }
        List<Map<String, Object>> tokenList = new ArrayList<>();
        byExchange.forEach((exType, tokens) ->
                tokenList.add(Map.of("exchangeType", exType, "tokens", tokens)));

        Map<String, Object> msg = Map.of(
                "correlationID", "unsub_" + System.currentTimeMillis(),
                "action", 2,
                "params", Map.of("mode", props.subscriptionMode(), "tokenList", tokenList)
        );
        try {
            ws.sendText(objectMapper.writeValueAsString(msg), true);
            log.info("Angel One unsubscribed from {} instruments", instruments.size());
        } catch (Exception e) {
            log.warn("Failed to send unsubscribe: {}", e.getMessage());
        }
    }

    private void subscribe(WebSocket ws) {
        List<InstrumentToken> instruments = instrumentRegistry.getActiveInstruments();
        if (instruments.isEmpty()) {
            log.warn("No active instruments in DB for Angel One streaming");
            return;
        }

        Map<Integer, List<String>> byExchange = new LinkedHashMap<>();
        for (InstrumentToken inst : instruments) {
            byExchange.computeIfAbsent(inst.exchangeType(), k -> new ArrayList<>()).add(inst.token());
        }

        List<Map<String, Object>> tokenList = new ArrayList<>();
        byExchange.forEach((exType, tokens) ->
                tokenList.add(Map.of("exchangeType", exType, "tokens", tokens)));

        Map<String, Object> msg = Map.of(
                "correlationID", "sensex_stream_" + System.currentTimeMillis(),
                "action", 1,
                "params", Map.of(
                        "mode", props.subscriptionMode(),
                        "tokenList", tokenList
                )
        );

        try {
            String json = objectMapper.writeValueAsString(msg);
            ws.sendText(json, true);
            log.info("Angel One subscribed: mode={} instruments={}", props.subscriptionMode(), instruments.size());
        } catch (Exception e) {
            log.error("Failed to send subscription: {}", e.getMessage());
        }
    }

    private void handleBinaryFrame(ByteBuffer data) {
        ByteBuffer combined;
        if (accumulator.hasRemaining()) {
            combined = ByteBuffer.allocate(accumulator.remaining() + data.remaining());
            combined.put(accumulator);
            combined.put(data);
            combined.flip();
        } else {
            combined = data;
        }

        combined.order(ByteOrder.LITTLE_ENDIAN);

        while (combined.remaining() >= HEADER_LEN) {
            int subscriptionMode = Byte.toUnsignedInt(combined.get(combined.position()));
            int requiredSize = wireSizeForSubscriptionMode(subscriptionMode);
            if (requiredSize < 0) {
                if (combined.hasRemaining()) {
                    combined.get();
                }
                continue;
            }
            if (combined.remaining() < requiredSize) {
                break;
            }

            ByteBuffer packet = combined.slice().order(ByteOrder.LITTLE_ENDIAN);
            packet.limit(requiredSize);
            combined.position(combined.position() + requiredSize);

            try {
                LiveTickData tick = parsePacket(packet);
                if (tick != null) {
                    dispatch(tick);
                }
            } catch (Exception e) {
                log.warn("Failed to parse tick packet: {}", e.getMessage());
            }
        }

        if (combined.hasRemaining()) {
            accumulator = ByteBuffer.allocate(combined.remaining());
            accumulator.put(combined);
            accumulator.flip();
        } else {
            accumulator = ByteBuffer.allocate(0);
        }
    }

    /**
     * Full on-wire packet length for one tick, per Angel SmartAPI binary v2
     * (see {@code SmartWebSocketV2._parse_binary_data} in smartapi-python).
     */
    private static int wireSizeForSubscriptionMode(int subscriptionMode) {
        return switch (subscriptionMode) {
            case 1 -> WIRE_LTP;
            case 2 -> WIRE_QUOTE;
            case 3 -> WIRE_SNAP_QUOTE;
            case 4 -> WIRE_DEPTH;
            default -> -1;
        };
    }

    private LiveTickData parsePacket(ByteBuffer buf) {
        int subscriptionMode = Byte.toUnsignedInt(buf.get());
        if (subscriptionMode == 4) {
            // Depth packets use a different layout; skip without emitting a bogus tick.
            buf.position(buf.limit());
            return null;
        }

        int exchangeType = Byte.toUnsignedInt(buf.get());

        byte[] tokenBytes = new byte[TOKEN_FIELD_LEN];
        buf.get(tokenBytes);
        String token = new String(tokenBytes, StandardCharsets.US_ASCII).trim().replace("\0", "");

        long seqNum = buf.getLong();
        long exchangeTs = buf.getLong();
        double ltp = buf.getLong() / 100.0;

        var builder = LiveTickData.builder()
                .subscriptionMode(subscriptionMode)
                .exchangeType(exchangeType)
                .token(token)
                .sequenceNumber(seqNum)
                .exchangeTimestampMs(exchangeTs)
                .lastTradedPrice(ltp);

        if (subscriptionMode == 2 || subscriptionMode == 3) {
            long ltq = buf.getLong();
            long avgPaise = buf.getLong();
            long vol = buf.getLong();
            double buy = buf.getDouble();
            double sell = buf.getDouble();
            double open = buf.getLong() / 100.0;
            double high = buf.getLong() / 100.0;
            double low = buf.getLong() / 100.0;
            double close = buf.getLong() / 100.0;
            builder.lastTradedQuantity(safeIntFromLong(ltq))
                    .averageTradedPrice(avgPaise / 100.0)
                    .volumeTraded(vol)
                    .totalBuyQuantity(buy)
                    .totalSellQuantity(sell)
                    .openPrice(open)
                    .highPrice(high)
                    .lowPrice(low)
                    .closePrice(close);
        }

        if (subscriptionMode == 3) {
            int skip = WIRE_SNAP_QUOTE - buf.position();
            if (skip > 0 && buf.remaining() >= skip) {
                buf.position(buf.position() + skip);
            }
        }

        return builder.build();
    }

    private static int safeIntFromLong(long v) {
        if (v > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (v < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) v;
    }

    private void dispatch(LiveTickData tick) {
        for (Consumer<LiveTickData> listener : listeners) {
            try {
                listener.accept(tick);
            } catch (Exception e) {
                log.warn("Tick listener error: {}", e.getMessage());
            }
        }
    }
}
