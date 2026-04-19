package com.sensex.optiontrader.integration.angelone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sensex.optiontrader.config.AngelOneProperties;
import com.sensex.optiontrader.config.AngelOneProperties.InstrumentToken;
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
 * Binary frame layout per instrument (Quote mode = 91 bytes):
 * <pre>
 *   [0]       subscription_mode   uint8
 *   [1]       exchange_type       uint8
 *   [2-26]    token               char[25]  (null-padded)
 *   [27-34]   sequence_number     int64 LE
 *   [35-42]   exchange_timestamp  int64 LE  (epoch ms)
 *   [43-46]   ltp                 int32 LE  (/100)
 *   --- Quote extras (mode >= 2) ---
 *   [47-50]   last_traded_qty     int32 LE
 *   [51-54]   avg_traded_price    int32 LE  (/100)
 *   [55-58]   volume_traded       int32 LE
 *   [59-66]   total_buy_qty       double LE
 *   [67-74]   total_sell_qty      double LE
 *   [75-78]   open                int32 LE  (/100)
 *   [79-82]   high                int32 LE  (/100)
 *   [83-86]   low                 int32 LE  (/100)
 *   [87-90]   close               int32 LE  (/100)
 * </pre>
 */
@Slf4j
@Component
public class AngelOneWebSocketClient {

    private static final int LTP_PACKET_SIZE = 47;
    private static final int QUOTE_PACKET_SIZE = 91;
    private static final int TOKEN_FIELD_LEN = 25;

    private final AngelOneProperties props;
    private final AngelOneAuthService authService;
    private final ObjectMapper objectMapper;
    private final List<Consumer<LiveTickData>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ByteBuffer accumulator = ByteBuffer.allocate(0);

    public AngelOneWebSocketClient(AngelOneProperties props,
                                   AngelOneAuthService authService,
                                   ObjectMapper objectMapper) {
        this.props = props;
        this.authService = authService;
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

    private void subscribe(WebSocket ws) {
        List<InstrumentToken> instruments = props.instruments();
        if (instruments == null || instruments.isEmpty()) {
            log.warn("No instruments configured for Angel One streaming");
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

        while (combined.remaining() >= LTP_PACKET_SIZE) {
            int mode = Byte.toUnsignedInt(combined.get(combined.position()));
            int requiredSize = mode >= 2 ? QUOTE_PACKET_SIZE : LTP_PACKET_SIZE;

            if (combined.remaining() < requiredSize) {
                break;
            }

            ByteBuffer packet = combined.slice().order(ByteOrder.LITTLE_ENDIAN);
            packet.limit(requiredSize);
            combined.position(combined.position() + requiredSize);

            try {
                LiveTickData tick = parsePacket(packet, mode);
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

    private LiveTickData parsePacket(ByteBuffer buf, int mode) {
        int subscriptionMode = Byte.toUnsignedInt(buf.get());
        int exchangeType = Byte.toUnsignedInt(buf.get());

        byte[] tokenBytes = new byte[TOKEN_FIELD_LEN];
        buf.get(tokenBytes);
        String token = new String(tokenBytes, StandardCharsets.US_ASCII).trim().replace("\0", "");

        long seqNum = buf.getLong();
        long exchangeTs = buf.getLong();
        double ltp = buf.getInt() / 100.0;

        var builder = LiveTickData.builder()
                .subscriptionMode(subscriptionMode)
                .exchangeType(exchangeType)
                .token(token)
                .sequenceNumber(seqNum)
                .exchangeTimestampMs(exchangeTs)
                .lastTradedPrice(ltp);

        if (mode >= 2 && buf.remaining() >= (QUOTE_PACKET_SIZE - LTP_PACKET_SIZE)) {
            builder.lastTradedQuantity(buf.getInt())
                    .averageTradedPrice(buf.getInt() / 100.0)
                    .volumeTraded(Integer.toUnsignedLong(buf.getInt()))
                    .totalBuyQuantity(buf.getDouble())
                    .totalSellQuantity(buf.getDouble())
                    .openPrice(buf.getInt() / 100.0)
                    .highPrice(buf.getInt() / 100.0)
                    .lowPrice(buf.getInt() / 100.0)
                    .closePrice(buf.getInt() / 100.0);
        }

        return builder.build();
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
