package com.sensex.optiontrader.service;

import com.sensex.optiontrader.config.AngelOneProperties;
import com.sensex.optiontrader.config.AppProperties;
import com.sensex.optiontrader.grpc.MlServiceClient;
import com.sensex.optiontrader.integration.angelone.AngelOneAuthService;
import com.sensex.optiontrader.integration.angelone.AngelOneMarketDataProvider;
import com.sensex.optiontrader.integration.angelone.AngelOneWebSocketClient;
import com.sensex.optiontrader.integration.angelone.LiveTickData;
import com.sensex.optiontrader.model.dto.response.PredictionResponse;
import com.sensex.optiontrader.repository.MlServiceConfigRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the Angel One live market stream and continuous live predictions:
 * <ol>
 *   <li>Authenticates and opens WebSocket on startup</li>
 *   <li>After the first tick, fires a baseline prediction for 1D</li>
 *   <li>On each tick: updates cache, broadcasts price via STOMP, forwards to ML, counts toward next prediction</li>
 *   <li>When the UI subscribes to a horizon (1D/3D/1W/1H), a periodic prediction loop starts:
 *       every {@code livePredictionIntervalMs} the latest OHLCV + live tick are sent to the ML service
 *       and the result is broadcast to {@code /topic/live-predictions}</li>
 *   <li>Horizon changes take effect immediately — the next prediction cycle uses the new horizon</li>
 *   <li>The UI can unsubscribe to stop the prediction loop</li>
 * </ol>
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
    private final AngelOneProperties props;
    private final AppProperties appProps;
    private final MlServiceConfigRepository configRepo;
    private final CacheManager cacheManager;

    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicBoolean baselineLoaded = new AtomicBoolean(false);

    /** Number of ticks received since the last live prediction was run. */
    private final AtomicInteger ticksSinceLastPrediction = new AtomicInteger(0);

    /** The horizon the UI is currently subscribed to (null = no active subscription). */
    private volatile String activeHorizon = null;

    private final ScheduledExecutorService streamScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "angel-one-stream");
        t.setDaemon(true);
        return t;
    });

    /** Dedicated scheduler for prediction work so it never blocks the stream/reconnect scheduler. */
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
                                   AngelOneProperties props,
                                   AppProperties appProps,
                                   MlServiceConfigRepository configRepo,
                                   CacheManager cacheManager) {
        this.authService = authService;
        this.wsClient = wsClient;
        this.provider = provider;
        this.notificationService = notificationService;
        this.mlServiceClient = mlServiceClient;
        this.marketDataService = marketDataService;
        this.props = props;
        this.appProps = appProps;
        this.configRepo = configRepo;
        this.cacheManager = cacheManager;
    }

    @PostConstruct
    void start() {
        wsClient.addTickListener(this::onTick);

        streamScheduler.execute(() -> {
            try {
                authService.login();
                wsClient.connect();
                log.info("Angel One live stream started");
            } catch (Exception e) {
                log.error("Initial stream connection failed: {} — will retry", e.getMessage());
                scheduleReconnect();
            }
        });

        streamScheduler.scheduleWithFixedDelay(this::healthCheck, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        stopLivePredictionLoop();
        wsClient.disconnect();
        authService.logout();
        streamScheduler.shutdownNow();
        predictionScheduler.shutdownNow();
        log.info("Angel One live stream stopped");
    }

    // ─── Tick handling ────────────────────────────────────────────────

    private void onTick(LiveTickData tick) {
        provider.updateTick(tick);
        broadcastToFrontend(tick);
        forwardToMlService(tick);
        ticksSinceLastPrediction.incrementAndGet();

        if (baselineLoaded.compareAndSet(false, true)) {
            predictionScheduler.execute(this::loadMlBaseline);
        }
    }

    private void loadMlBaseline() {
        runPrediction("1D");
    }

    // ─── Live prediction loop (driven by the UI's active horizon) ─────

    /**
     * Called when the UI subscribes to (or changes) a prediction horizon.
     * Triggers an immediate prediction and starts / restarts the periodic loop.
     */
    public void subscribeLivePredictions(String horizon) {
        String h = normalizeHorizon(horizon);
        String previous = activeHorizon;
        activeHorizon = h;
        ticksSinceLastPrediction.set(0);

        log.info("[LIVE PREDICT] Horizon set to {} (was {})", h, previous);

        predictionScheduler.execute(() -> runPrediction(h));
        startLivePredictionLoop();
    }

    /**
     * Called when the UI no longer needs live predictions (e.g. navigated away).
     */
    public void unsubscribeLivePredictions() {
        log.info("[LIVE PREDICT] Unsubscribed (was {})", activeHorizon);
        activeHorizon = null;
        stopLivePredictionLoop();
    }

    /**
     * Returns the currently active horizon, or null if no live prediction loop is running.
     */
    public String getActiveHorizon() {
        return activeHorizon;
    }

    private synchronized void startLivePredictionLoop() {
        stopLivePredictionLoop();

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

    /**
     * Executed on each scheduled cycle. Skips if no new ticks arrived since the last
     * prediction (avoids wasting ML resources when the market is idle).
     */
    private void livePredictionCycle() {
        String h = activeHorizon;
        if (h == null) {
            return;
        }
        int pending = ticksSinceLastPrediction.getAndSet(0);
        if (pending == 0) {
            log.debug("[LIVE PREDICT] No new ticks since last prediction — skipping cycle");
            return;
        }
        log.debug("[LIVE PREDICT] {} ticks since last prediction — running for horizon {}", pending, h);
        runPrediction(h);
    }

    // ─── Prediction execution (shared by baseline + live loop) ────────

    /**
     * Fetches OHLCV + VIX for the given horizon, calls the ML/AI gRPC,
     * caches the result, and broadcasts to {@code /topic/live-predictions}.
     */
    private void runPrediction(String horizon) {
        OhlcvSpec spec = ohlcvSpecFor(horizon);
        boolean useAi = isAiEngine();
        String engine = useAi ? "AI" : "ML";

        try {
            log.info("[PREDICT] [{}] engine={} fetching {} {} OHLCV", horizon, engine, spec.period, spec.interval);
            var ohlcv = marketDataService.getSensexOhlcv(spec.period, spec.interval);
            var vix = marketDataService.getIndiaVixHistory();
            LiveTickData liveTick = latestPrimaryTick();

            var result = useAi
                    ? mlServiceClient.getGeminiPrediction(horizon, ohlcv, vix, liveTick)
                    : mlServiceClient.getPrediction(horizon, ohlcv, vix, liveTick);

            updatePredictionCache(horizon, result);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("engine", engine);
            payload.put("horizon", result.getHorizon());
            payload.put("direction", result.getDirection() != null ? result.getDirection().name() : "NEUTRAL");
            payload.put("magnitude", result.getMagnitude());
            payload.put("confidence", result.getConfidence());
            payload.put("predictedVolatility", result.getPredictedVolatility());
            payload.put("currentSensex", result.getCurrentSensex());
            payload.put("targetSensex", result.getTargetSensex());
            payload.put("predictionDate", result.getPredictionDate() != null ? result.getPredictionDate().toString() : "");
            payload.put("live", activeHorizon != null);
            notificationService.broadcastPrediction(payload);

            log.info("[PREDICT] [{}] {} {} conf={}% mag={}%",
                    horizon, engine, result.getDirection(), result.getConfidence(), result.getMagnitude());
        } catch (Exception e) {
            log.warn("Prediction [{}] failed: {}", horizon, e.getMessage());
        }
    }

    /**
     * Legacy entry point kept for backward compatibility — triggers a one-shot
     * prediction without starting the live loop.
     */
    public void requestPrediction(String horizon) {
        String h = normalizeHorizon(horizon);
        predictionScheduler.execute(() -> runPrediction(h));
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private static String normalizeHorizon(String horizon) {
        return (horizon == null || horizon.isBlank()) ? "1D" : horizon.trim().toUpperCase();
    }

    private void updatePredictionCache(String horizon, PredictionResponse result) {
        try {
            var cache = cacheManager.getCache("predictions");
            if (cache != null) {
                cache.put("v5-" + horizon, result);
            }
        } catch (Exception e) {
            log.debug("Failed to update prediction cache: {}", e.getMessage());
        }
    }

    private record OhlcvSpec(String period, String interval) {}

    private static OhlcvSpec ohlcvSpecFor(String horizon) {
        return switch (horizon) {
            case "1H" -> new OhlcvSpec("1M", "5M");
            case "3D" -> new OhlcvSpec("1Y", "1D");
            case "1W" -> new OhlcvSpec("2Y", "1D");
            default   -> new OhlcvSpec("1Y", "1D");
        };
    }

    private boolean isAiEngine() {
        return configRepo.findByConfigKey("prediction_engine")
                .map(c -> "AI".equalsIgnoreCase(c.getConfigValue()))
                .orElse(false);
    }

    private LiveTickData latestPrimaryTick() {
        if (props.instruments() == null || props.instruments().isEmpty()) return null;
        return provider.getLatestTick(props.instruments().get(0).token());
    }

    private void broadcastToFrontend(LiveTickData tick) {
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
            notificationService.broadcastPrice(payload);
        } catch (Exception e) {
            log.debug("Failed to broadcast tick: {}", e.getMessage());
        }
    }

    private void forwardToMlService(LiveTickData tick) {
        try {
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

    // ─── Reconnection ─────────────────────────────────────────────────

    private void healthCheck() {
        if (!wsClient.isConnected()) {
            log.warn("Angel One WebSocket disconnected — scheduling reconnect");
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!reconnecting.compareAndSet(false, true)) {
            return;
        }
        long delay = props.reconnectDelayMs();
        streamScheduler.schedule(() -> {
            try {
                if (!authService.isAuthenticated()) {
                    authService.login();
                }
                wsClient.connect();
                reconnecting.set(false);
                baselineLoaded.set(false);
                log.info("Angel One WebSocket reconnected");
            } catch (Exception e) {
                reconnecting.set(false);
                log.error("Reconnect failed: {} — will retry in {}ms", e.getMessage(), delay);
                scheduleReconnect();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }
}
