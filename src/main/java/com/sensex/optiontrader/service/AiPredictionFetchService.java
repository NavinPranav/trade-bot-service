package com.sensex.optiontrader.service;

import com.sensex.optiontrader.config.AppProperties;
import com.sensex.optiontrader.exception.MlServiceUnavailableException;
import com.sensex.optiontrader.grpc.MlRestClient;
import com.sensex.optiontrader.grpc.MlServiceClient;
import com.sensex.optiontrader.integration.MarketDataProvider;
import com.sensex.optiontrader.integration.angelone.LiveTickData;
import com.sensex.optiontrader.model.dto.response.PredictionResponse;
import com.sensex.optiontrader.model.entity.Prediction;
import com.sensex.optiontrader.repository.PredictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.ZoneId;

/**
 * Cached ML fetch only — risk limits are applied afterwards in {@link PredictionService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiPredictionFetchService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Package-private so unit tests can assert the intra-day vs daily mapping. */
    record HorizonSpec(String period, String interval) {}

    private final PredictionRepository repo;
    private final MlServiceClient ml;
    private final MlRestClient mlRest;
    private final MarketDataService marketData;
    private final MarketDataProvider marketDataProvider;
    private final InstrumentRegistry instrumentRegistry;
    private final AppProperties appProperties;
    private final OptionsChainService optionsChainService;
    private final AiModelService aiModelService;

    /**
     * OHLCV (period, interval) for each horizon.
     * <p>
     * <b>Intra-day horizons must use intra-day candles</b> — feeding 1-day bars to a 15M-target
     * predictor produces ATR/EMA/regime values that look correct (no NaN) but mean nothing
     * about the next 15 minutes, which is why the AI was stuck on HOLD/SIDEWAYS regardless of
     * the live price action. The intra-day mapping here intentionally mirrors
     * {@link LiveMarketStreamService#ohlcvSpecFor(String)} so REST fetches and live-stream
     * predictions both see the same candle resolution.
     * <ul>
     *   <li>5M  → 3 days of 1-minute candles  (~780 bars; satisfies the 60-bar floor)</li>
     *   <li>15M → 5 days of 5-minute candles  (~375 bars; satisfies the 120-bar floor)</li>
     *   <li>30M → 7 days of 5-minute candles  (~525 bars; satisfies the 80-bar floor)</li>
     *   <li>1H  → 1 month of 5-minute candles</li>
     *   <li>1D / 3D / 1W → daily candles for swing context</li>
     * </ul>
     */
    static HorizonSpec horizonSpec(String horizon) {
        String h = horizon == null ? "15M" : horizon.trim().toUpperCase();
        return switch (h) {
            case "5M"  -> new HorizonSpec("3D", "1M");
            case "15M" -> new HorizonSpec("5D", "5M");
            case "30M" -> new HorizonSpec("7D", "5M");
            case "1H"  -> new HorizonSpec("1M", "5M");
            case "1D"  -> new HorizonSpec("1Y", "1D");
            case "3D"  -> new HorizonSpec("6M", "1D");
            case "1W"  -> new HorizonSpec("2Y", "1D");
            default    -> new HorizonSpec("5D", "5M"); // safe intra-day default (was: 1Y/1D — wrong for intraday targets)
        };
    }

    // NOTE: cache key bumped from v8 → v9 alongside the intra-day fix in {@link #horizonSpec(String)}.
    // Without the bump, predictions cached during the daily-candle era (where 15M/30M/5M ran on 1Y/1D bars)
    // would keep being served until TTL expiry and mask the fix in prod.
    @Cacheable(
            value = "predictions",
            key = "'v9-AI-'+#horizon+'-u'+#userId+'-tok'+@instrumentRegistry.primaryTokenOrNone(#userId)"
    )
    public PredictionResponse fetchAiPrediction(String horizon, Long userId) {
        HorizonSpec spec = horizonSpec(horizon);
        var ohlcv = marketData.getOhlcvForUser(userId, spec.period(), spec.interval());
        var indiaVixHistory = marketData.getIndiaVixHistory();
        LiveTickData liveTick = latestPrimaryTick(userId);

        int minBars = minBarsForHorizon(horizon);
        int actualBars = ohlcv != null ? ohlcv.size() : 0;
        AppProperties.Transport transport = appProperties.getMlService().getTransport();
        boolean restConfigured = mlRest.isConfigured();

        // Resolve active AI tool so we can route correctly (OpenAI has no gRPC path).
        String activeToolName = aiModelService.getActiveToolName();
        boolean isOpenAi = "OPENAI".equalsIgnoreCase(activeToolName);
        String engine = activeToolName != null ? activeToolName : "AI";

        // One concise per-call log so deploys are easy to verify in prod:
        //   ML-CALL fetch userId=… horizon=… spec=5D/5M bars=375 minBars=120 transport=REST restConfigured=true tool=OPENAI
        // If transport=AUTO appears here when you expected REST → env var didn't reach the JVM.
        log.info("ML-CALL fetch userId={} horizon={} spec={}/{} bars={} minBars={} transport={} restConfigured={} tool={}",
                userId, horizon, spec.period(), spec.interval(), actualBars, minBars, transport, restConfigured, engine);

        if (actualBars < minBars) {
            log.warn("AI fetch userId={} horizon={} insufficient bars (have={} required={}); falling back to last stored prediction",
                    userId, horizon, actualBars, minBars);
            return dbFallbackOrThrow(horizon,
                    new MlServiceUnavailableException(
                            "Insufficient OHLCV bars for horizon " + horizon + " (have=" + actualBars + " required=" + minBars + ")",
                            null));
        }

        // Fetch options chain (non-fatal — empty on any error, prompt shows N/A)
        var optionsChain = fetchOptionsChainSafe(userId);

        // OpenAI has no gRPC support — always route through REST.
        // Also covers REST-only deployments (e.g. Render free tier).
        if (transport == AppProperties.Transport.REST || isOpenAi) {
            if (!restConfigured) {
                throw new MlServiceUnavailableException(
                        isOpenAi
                                ? "OpenAI prediction requires REST transport but ML_SERVICE_HTTP_URL is not configured"
                                : "ML transport is REST but ML_SERVICE_HTTP_URL is not configured",
                        null);
            }
            log.debug("prediction={} (fetch, REST) userId={} horizon={}", engine, userId, horizon);
            try {
                return mlRest.predict(engine, horizon, ohlcv, indiaVixHistory, liveTick, userId, optionsChain);
            } catch (Exception restErr) {
                log.warn("REST prediction failed: {}", restErr.getMessage());
                return dbFallbackOrThrow(horizon, restErr);
            }
        }

        try {
            log.debug("prediction={} (fetch, gRPC) userId={} horizon={}", engine, userId, horizon);
            return ml.getGeminiPrediction(horizon, ohlcv, indiaVixHistory, liveTick, userId, optionsChain);
        } catch (Exception grpcErr) {
            // GRPC strict mode: rethrow without REST fallback.
            if (transport == AppProperties.Transport.GRPC) {
                log.warn("gRPC prediction failed (GRPC-only mode, no fallback): {}", grpcErr.getMessage());
                return dbFallbackOrThrow(horizon, grpcErr);
            }
            log.warn("gRPC prediction failed, trying HTTP REST fallback: {}", grpcErr.getMessage());

            if (restConfigured) {
                try {
                    return mlRest.predict(engine, horizon, ohlcv, indiaVixHistory, liveTick, userId, optionsChain);
                } catch (Exception restErr) {
                    log.warn("HTTP REST prediction also failed: {}", restErr.getMessage());
                }
            }

            var dbFallback = repo.findTopByHorizonOrderByPredictionDateDesc(horizon);
            if (dbFallback.isPresent()) {
                Prediction p = dbFallback.get();
                return PredictionResponse.builder()
                        .predictionDate(p.getPredictionDate())
                        .predictionTimestampMs(p.getCreatedAt() != null
                                ? p.getCreatedAt().atZone(IST).toInstant().toEpochMilli()
                                : System.currentTimeMillis())
                        .horizon(p.getHorizon())
                        .direction(p.getDirection())
                        .magnitude(p.getMagnitude())
                        .confidence(p.getConfidence())
                        .predictedVolatility(p.getPredictedVolatility())
                        .currentSensex(p.getCurrentSensex())
                        .entryPrice(p.getEntryPrice())
                        .stopLoss(p.getStopLoss())
                        .targetSensex(p.getTargetSensex())
                        .riskReward(p.getRiskReward())
                        .build();
            }
            throw new MlServiceUnavailableException(
                    "ML service unreachable and no cached predictions for horizon: " + horizon, grpcErr);
        }
    }

    private java.util.List<java.util.Map<String, Object>> fetchOptionsChainSafe(Long userId) {
        try {
            return optionsChainService.getOptionsChainForUser(userId);
        } catch (Exception e) {
            log.debug("Options chain fetch failed (non-fatal): {}", e.getMessage());
            return java.util.List.of();
        }
    }

    private LiveTickData latestPrimaryTick(Long userId) {
        return instrumentRegistry.getPrimaryForUser(userId)
                .map(i -> marketDataProvider.getLatestTick(i.token()))
                .orElse(null);
    }

    /**
     * Resolves the minimum number of OHLCV bars we'll send for a horizon.
     * Falls back to {@code defaultMinBars} from config when the horizon isn't mapped.
     */
    private int minBarsForHorizon(String horizon) {
        AppProperties.MlService ml = appProperties.getMlService();
        java.util.Map<String, Integer> map = ml.getMinBarsByHorizon();
        if (horizon != null && map != null && map.containsKey(horizon.toUpperCase())) {
            Integer v = map.get(horizon.toUpperCase());
            if (v != null && v > 0) return v;
        }
        return Math.max(1, ml.getDefaultMinBars());
    }

    /** Common DB fallback used by REST-only and GRPC-only failure paths. */
    private PredictionResponse dbFallbackOrThrow(String horizon, Exception cause) {
        var dbFallback = repo.findTopByHorizonOrderByPredictionDateDesc(horizon);
        if (dbFallback.isPresent()) {
            Prediction p = dbFallback.get();
            return PredictionResponse.builder()
                    .predictionDate(p.getPredictionDate())
                    .predictionTimestampMs(p.getCreatedAt() != null
                            ? p.getCreatedAt().atZone(IST).toInstant().toEpochMilli()
                            : System.currentTimeMillis())
                    .horizon(p.getHorizon())
                    .direction(p.getDirection())
                    .magnitude(p.getMagnitude())
                    .confidence(p.getConfidence())
                    .predictedVolatility(p.getPredictedVolatility())
                    .currentSensex(p.getCurrentSensex())
                    .entryPrice(p.getEntryPrice())
                    .stopLoss(p.getStopLoss())
                    .targetSensex(p.getTargetSensex())
                    .riskReward(p.getRiskReward())
                    .build();
        }
        throw new MlServiceUnavailableException(
                "ML service unreachable and no cached predictions for horizon: " + horizon, cause);
    }
}
