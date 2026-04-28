package com.sensex.optiontrader.service;

import com.sensex.optiontrader.config.AngelOneProperties;
import com.sensex.optiontrader.config.AppProperties;
import com.sensex.optiontrader.grpc.MlServiceClient;
import com.sensex.optiontrader.integration.angelone.AngelOneAuthService;
import com.sensex.optiontrader.integration.angelone.AngelOneMarketDataProvider;
import com.sensex.optiontrader.integration.angelone.AngelOneWebSocketClient;
import com.sensex.optiontrader.integration.angelone.LiveTickData;
import com.sensex.optiontrader.model.dto.response.PredictionResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Angel One market stream starts only after at least one authenticated STOMP session connects.
 * Live prices and predictions are scoped per user (principal name = email) and per-user preferred instrument.
 */
@Slf4j
@Service
public class LiveMarketStreamService {

    private final AngelOneAuthService authService;
    private final AngelOneWebSocketClient wsClient;
    private final AngelOneMarketDataProvider provider;
    private final NotificationService notificationService;
    private final MlServiceClient mlServiceClient;
    private final MarketDataService marketDataService;
    private final InstrumentRegistry instrumentRegistry;
    private final AngelOneProperties angelOneProps;
    private final AppProperties appProps;
    private final CacheManager cacheManager;

    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicBoolean streamStarted = new AtomicBoolean(false);

    private record StompSessionInfo(Long userId, String email) {}

    private static final class PredictionSubscriber {
        final String email;
        volatile String horizon;

        PredictionSubscriber(String email, String horizon) {
            this.email = email;
            this.horizon = horizon;
        }
    }

    private final ConcurrentHashMap<String, StompSessionInfo> stompSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, PredictionSubscriber> predictionSubscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> tickCounters = new ConcurrentHashMap<>();

    private final ScheduledExecutorService streamScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "angel-one-stream");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService predictionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-prediction");
        t.setDaemon(true);
        return t;
    });

    private volatile ScheduledFuture<?> livePredictionTask;

    public LiveMarketStreamService(AngelOneAuthService authService,
                                   AngelOneWebSocketClient wsClient,
                                   AngelOneMarketDataProvider provider,
                                   NotificationService notificationService,
                                   MlServiceClient mlServiceClient,
                                   MarketDataService marketDataService,
                                   InstrumentRegistry instrumentRegistry,
                                   AngelOneProperties angelOneProps,
                                   AppProperties appProps,
                                   CacheManager cacheManager) {
        this.authService = authService;
        this.wsClient = wsClient;
        this.provider = provider;
        this.notificationService = notificationService;
        this.mlServiceClient = mlServiceClient;
        this.marketDataService = marketDataService;
        this.instrumentRegistry = instrumentRegistry;
        this.angelOneProps = angelOneProps;
        this.appProps = appProps;
        this.cacheManager = cacheManager;
    }

    @PostConstruct
    void init() {
        wsClient.addTickListener(this::onTick);
        streamScheduler.scheduleWithFixedDelay(this::healthCheck, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        stopLivePredictionLoop();
        predictionSubscribers.clear();
        stompSessions.clear();
        tickCounters.clear();
        wsClient.disconnect();
        authService.logout();
        streamStarted.set(false);
        streamScheduler.shutdownNow();
        predictionScheduler.shutdownNow();
        log.info("Angel One live stream stopped");
    }

    /** Called when a STOMP client subscribes to a user queue (live prices / predictions). */
    public void registerStompSession(String sessionId, Long userId, String email) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(email, "email");
        stompSessions.put(sessionId, new StompSessionInfo(userId, email));
        ensureStreamStarted();
        log.info("STOMP session {} registered for user {}", sessionId, userId);
    }

    /** Called on WebSocket/STOMP disconnect. */
    public void onStompSessionClosed(String sessionId) {
        StompSessionInfo removed = stompSessions.remove(sessionId);
        if (removed == null) {
            return;
        }
        log.info("STOMP session {} closed for user {}", sessionId, removed.userId());
        if (stompSessions.values().stream().noneMatch(s -> s.userId().equals(removed.userId()))) {
            unsubscribeLivePredictions(removed.userId());
        }
        if (stompSessions.isEmpty()) {
            shutdownMarketDataPipeline();
        }
    }

    private synchronized void ensureStreamStarted() {
        if (streamStarted.get()) {
            return;
        }
        streamScheduler.execute(() -> {
            try {
                authService.login();
                instrumentRegistry.refreshStreamingSubscriptions();
                wsClient.connect();
                streamStarted.set(true);
                log.info("Angel One live stream started (first authenticated client)");
            } catch (Exception e) {
                streamStarted.set(false);
                log.error("Angel One stream start failed: {}", e.getMessage());
                scheduleReconnect();
            }
        });
    }

    private synchronized void shutdownMarketDataPipeline() {
        log.info("No STOMP clients — stopping Angel One stream and prediction loop");
        stopLivePredictionLoop();
        predictionSubscribers.clear();
        tickCounters.clear();
        wsClient.disconnect();
        streamStarted.set(false);
    }

    private void onTick(LiveTickData tick) {
        provider.updateTick(tick);
        broadcastToSubscribedUsers(tick);
        forwardToMlService(tick);

        for (Long userId : predictionSubscribers.keySet()) {
            instrumentRegistry.getPrimaryForUser(userId).ifPresent(inst -> {
                if (inst.token().equals(tick.getToken())) {
                    tickCounters.computeIfAbsent(userId, k -> new AtomicInteger(0)).incrementAndGet();
                }
            });
        }
    }

    private void broadcastToSubscribedUsers(LiveTickData tick) {
        try {
            String name = provider.resolveInstrumentName(tick.getToken());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("symbol", name);
            payload.put("token", tick.getToken());
            payload.put("exchangeType", tick.getExchangeType());
            payload.put("ltp", tick.getLastTradedPrice());
            payload.put("open", tick.getOpenPrice());
            payload.put("high", tick.getHighPrice());
            payload.put("low", tick.getLowPrice());
            payload.put("close", tick.getClosePrice());
            payload.put("change", tick.change());
            payload.put("changePct", tick.changePct());
            payload.put("volume", tick.getVolumeTraded());
            payload.put("timestamp", tick.getExchangeTimestampMs());

            Set<Long> seen = ConcurrentHashMap.newKeySet();
            for (StompSessionInfo s : stompSessions.values()) {
                if (!seen.add(s.userId())) {
                    continue;
                }
                instrumentRegistry.getPrimaryForUser(s.userId()).ifPresent(inst -> {
                    if (inst.token().equals(tick.getToken())) {
                        notificationService.sendPriceToUser(s.email(), payload);
                    }
                });
            }
        } catch (Exception e) {
            log.debug("Failed to broadcast tick: {}", e.getMessage());
        }
    }

    /**
     * Called when the UI subscribes to (or changes) a prediction horizon.
     */
    public void subscribeLivePredictions(Long userId, String email, String horizon) {
        String h = normalizeHorizon(horizon);
        predictionSubscribers.compute(userId, (k, v) -> {
            if (v == null) {
                return new PredictionSubscriber(email, h);
            }
            v.horizon = h;
            return v;
        });
        tickCounters.put(userId, new AtomicInteger(0));

        log.info("[LIVE PREDICT] user {} horizon {}", userId, h);
        predictionScheduler.execute(() -> runPrediction(h, userId, email));
        startLivePredictionLoopIfNeeded();
    }

    public void unsubscribeLivePredictions(Long userId) {
        PredictionSubscriber removed = predictionSubscribers.remove(userId);
        tickCounters.remove(userId);
        if (removed != null) {
            log.info("[LIVE PREDICT] user {} unsubscribed (was {})", userId, removed.horizon);
        }
        if (predictionSubscribers.isEmpty()) {
            stopLivePredictionLoop();
        }
    }

    public void requestPrediction(String horizon, Long userId, String email) {
        String h = normalizeHorizon(horizon);
        predictionScheduler.execute(() -> runPrediction(h, userId, email));
    }

    private synchronized void startLivePredictionLoopIfNeeded() {
        if (livePredictionTask != null && !livePredictionTask.isDone()) {
            return;
        }
        long intervalMs = appProps.getMlService().getLivePredictionIntervalMs();
        livePredictionTask = predictionScheduler.scheduleWithFixedDelay(
                this::livePredictionCycle,
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS
        );
        log.info("[LIVE PREDICT] Prediction loop started — interval {}ms", intervalMs);
    }

    private synchronized void stopLivePredictionLoop() {
        ScheduledFuture<?> task = livePredictionTask;
        if (task != null && !task.isDone()) {
            task.cancel(false);
            log.info("[LIVE PREDICT] Prediction loop stopped");
        }
        livePredictionTask = null;
    }

    private void livePredictionCycle() {
        if (predictionSubscribers.isEmpty()) {
            return;
        }
        if (!isWithinTradingHours()) {
            log.debug("[LIVE PREDICT] Market closed — skipping prediction cycle");
            return;
        }
        for (var e : predictionSubscribers.entrySet()) {
            Long userId = e.getKey();
            PredictionSubscriber sub = e.getValue();
            String h = sub.horizon;
            AtomicInteger cnt = tickCounters.get(userId);
            if (cnt == null) {
                continue;
            }
            int pending = cnt.getAndSet(0);
            if (pending > 0) {
                log.debug("[LIVE PREDICT] {} ticks for user {} horizon {}", pending, userId, h);
                runPrediction(h, userId, sub.email);
            }
        }
    }

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final LocalTime SQUAREOFF_WARN = LocalTime.of(15, 0);

    private boolean isWithinTradingHours() {
        LocalTime now = LocalTime.now(IST_ZONE);
        return !now.isBefore(MARKET_OPEN) && now.isBefore(MARKET_CLOSE);
    }

    private boolean isApproachingSquareOff() {
        LocalTime now = LocalTime.now(IST_ZONE);
        return !now.isBefore(SQUAREOFF_WARN) && now.isBefore(MARKET_CLOSE);
    }

    /** Minutes remaining until market close (negative if already past close). */
    private int minutesToClose() {
        LocalTime now = LocalTime.now(IST_ZONE);
        return (int) java.time.Duration.between(now, MARKET_CLOSE).toMinutes();
    }

    private void runPrediction(String horizon, Long userId, String email) {
        OhlcvSpec spec = ohlcvSpecFor(horizon);
        try {
            log.info("[PREDICT] user={} [{}] AI fetching {} {} OHLCV", userId, horizon, spec.period, spec.interval);
            var ohlcv = marketDataService.getOhlcvForUser(userId, spec.period, spec.interval);
            var vix = marketDataService.getIndiaVixHistory();
            LiveTickData liveTick = latestPrimaryTick(userId);

            var result = mlServiceClient.getGeminiPrediction(horizon, ohlcv, vix, liveTick, userId);

            updatePredictionCache(horizon, userId, result);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("engine", "AI");
            payload.put("horizon", result.getHorizon());
            payload.put("direction", result.getDirection() != null ? result.getDirection().name() : "HOLD");
            payload.put("magnitude", result.getMagnitude());
            payload.put("confidence", result.getConfidence());
            payload.put("predictedVolatility", result.getPredictedVolatility());
            payload.put("currentSensex", result.getCurrentSensex());
            payload.put("targetSensex", result.getTargetSensex());
            payload.put("currentPrice", result.getCurrentPrice());
            payload.put("targetPrice", result.getTargetPrice());
            // ── Intra-day trading levels ──
            payload.put("entryPrice", result.getEntryPrice());
            payload.put("stopLoss", result.getStopLoss());
            payload.put("riskReward", result.getRiskReward());
            payload.put("validMinutes", result.getValidMinutes());
            payload.put("noTradeZone", result.getNoTradeZone() != null && result.getNoTradeZone());
            payload.put("predictionTimestampMs", result.getPredictionTimestampMs() != null
                    ? result.getPredictionTimestampMs() : System.currentTimeMillis());
            // ── Session context ──
            payload.put("squareOffWarning", isApproachingSquareOff());
            payload.put("minutesToClose", minutesToClose());
            payload.put("marketOpen", isWithinTradingHours());
            // ── Meta ──
            payload.put("predictionDate", result.getPredictionDate() != null ? result.getPredictionDate().toString() : "");
            if (result.getAiQuotaNotice() != null && !result.getAiQuotaNotice().isBlank()) {
                payload.put("aiQuotaNotice", result.getAiQuotaNotice());
            }
            if (result.getPredictionReason() != null && !result.getPredictionReason().isBlank()) {
                payload.put("predictionReason", result.getPredictionReason());
            }
            payload.put("live", predictionSubscribers.containsKey(userId));
            notificationService.sendPredictionToUser(email, payload);

            log.info("[PREDICT] user={} [{}] {} conf={}% entry={} SL={} TP={} RR={}",
                    userId, horizon,
                    result.getDirection(), result.getConfidence(),
                    result.getEntryPrice(), result.getStopLoss(),
                    result.getTargetPrice(), result.getRiskReward());
        } catch (Exception e) {
            log.warn("Prediction user={} [{}] failed: {}", userId, horizon, e.getMessage());
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("engine", "AI");
                payload.put("horizon", horizon);
                payload.put("direction", "HOLD");
                payload.put("magnitude", 0);
                payload.put("confidence", 0);
                payload.put("predictedVolatility", 0);
                payload.put("predictionTimestampMs", System.currentTimeMillis());
                payload.put("squareOffWarning", isApproachingSquareOff());
                payload.put("minutesToClose", minutesToClose());
                payload.put("marketOpen", isWithinTradingHours());
                payload.put("live", predictionSubscribers.containsKey(userId));
                payload.put("liveError", true);
                String msg = e.getMessage() == null ? "Live AI prediction failed" : e.getMessage().trim();
                if (msg.length() > 280) {
                    msg = msg.substring(0, 280) + "...";
                }
                payload.put("liveErrorMessage", msg);
                payload.put(
                        "aiQuotaNotice",
                        "Live AI update failed on backend. Showing HOLD placeholder until retry succeeds."
                );
                payload.put(
                        "predictionReason",
                        "The latest live inference call failed. This update is a fallback and not a fresh model forecast."
                );
                notificationService.sendPredictionToUser(email, payload);
            } catch (Exception notifyErr) {
                log.debug("Failed to send live prediction failure payload: {}", notifyErr.getMessage());
            }
        }
    }

    public void onInstrumentSwitch() {
        log.info("Preferred instrument changed — resubscribing Angel One");
        streamScheduler.execute(() -> {
            try {
                instrumentRegistry.refreshStreamingSubscriptions();
                if (wsClient.isConnected()) {
                    wsClient.resubscribe();
                }
            } catch (Exception e) {
                log.warn("Resubscribe failed, scheduling reconnect: {}", e.getMessage());
                scheduleReconnect();
            }
        });
    }

    private LiveTickData latestPrimaryTick(Long userId) {
        return instrumentRegistry.getPrimaryForUser(userId)
                .map(i -> provider.getLatestTick(i.token()))
                .orElse(null);
    }

    private void forwardToMlService(LiveTickData tick) {
        try {
            if (instrumentRegistry.findByName("INDIA VIX")
                    .map(v -> v.token().equals(tick.getToken()))
                    .orElse(false)) {
                return;
            }
            String name = provider.resolveInstrumentName(tick.getToken());
            mlServiceClient.sendLiveTick(
                    name,
                    tick.getExchangeType(),
                    tick.getToken(),
                    tick.getLastTradedPrice(),
                    tick.getOpenPrice(),
                    tick.getHighPrice(),
                    tick.getLowPrice(),
                    tick.getClosePrice(),
                    tick.change(),
                    tick.changePct(),
                    tick.getVolumeTraded(),
                    tick.getExchangeTimestampMs()
            );
        } catch (Exception e) {
            log.debug("Failed to forward tick to ML: {}", e.getMessage());
        }
    }

    private void updatePredictionCache(String horizon, Long userId, PredictionResponse result) {
        try {
            var cache = cacheManager.getCache("predictions");
            if (cache != null) {
                cache.put(
                        "v8-AI-" + horizon + "-u" + userId + "-tok" + instrumentRegistry.primaryTokenOrNone(userId),
                        result);
            }
        } catch (Exception e) {
            log.debug("Failed to update prediction cache: {}", e.getMessage());
        }
    }

    private static String normalizeHorizon(String horizon) {
        if (horizon == null || horizon.isBlank()) return "15M";
        return switch (horizon.trim().toUpperCase()) {
            case "5M", "5MIN", "5MINS", "5MINUTE" -> "5M";
            case "30M", "30MIN", "30MINS", "30MINUTE" -> "30M";
            default -> "15M";
        };
    }

    private record OhlcvSpec(String period, String interval) {}

    /**
     * Returns the historical OHLCV period and candle interval for each intra-day horizon.
     * Using more days of data gives the AI better context for support/resistance levels.
     *   5M  → last 3 days of 1-minute candles  (~780 bars, ~13 hours of context)
     *   15M → last 5 days of 5-minute candles  (~375 bars, ~31 hours of context)
     *   30M → last 7 days of 5-minute candles  (~525 bars, ~44 hours of context)
     */
    private static OhlcvSpec ohlcvSpecFor(String horizon) {
        return switch (horizon) {
            case "5M"  -> new OhlcvSpec("3D", "1M");
            case "30M" -> new OhlcvSpec("7D", "5M");
            default    -> new OhlcvSpec("5D", "5M");   // 15M default
        };
    }

    private void healthCheck() {
        if (!streamStarted.get()) {
            return;
        }
        if (!wsClient.isConnected()) {
            log.warn("Angel One WebSocket disconnected — scheduling reconnect");
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!reconnecting.compareAndSet(false, true)) {
            return;
        }
        long delay = angelOneProps.reconnectDelayMs();
        streamScheduler.schedule(() -> {
            try {
                if (!authService.isAuthenticated()) {
                    authService.login();
                }
                instrumentRegistry.refreshStreamingSubscriptions();
                wsClient.connect();
                streamStarted.set(true);
                reconnecting.set(false);
                log.info("Angel One WebSocket reconnected");
            } catch (Exception e) {
                reconnecting.set(false);
                log.error("Reconnect failed: {} — will retry in {}ms", e.getMessage(), delay);
                scheduleReconnect();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }
}
