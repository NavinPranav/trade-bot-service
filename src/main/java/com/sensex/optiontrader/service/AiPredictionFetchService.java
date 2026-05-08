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

    private record HorizonSpec(String period, String interval) {}

    private final PredictionRepository repo;
    private final MlServiceClient ml;
    private final MlRestClient mlRest;
    private final MarketDataService marketData;
    private final MarketDataProvider marketDataProvider;
    private final InstrumentRegistry instrumentRegistry;
    private final AppProperties appProperties;

    private static HorizonSpec horizonSpec(String horizon) {
        String h = horizon == null ? "1D" : horizon.trim().toUpperCase();
        return switch (h) {
            case "1D" -> new HorizonSpec("1Y", "1D");
            case "3D" -> new HorizonSpec("6M", "1D");
            case "1W" -> new HorizonSpec("2Y", "1D");
            case "1H" -> new HorizonSpec("1M", "5M");
            default -> new HorizonSpec("1Y", "1D");
        };
    }

    @Cacheable(
            value = "predictions",
            key = "'v8-AI-'+#horizon+'-u'+#userId+'-tok'+@instrumentRegistry.primaryTokenOrNone(#userId)"
    )
    public PredictionResponse fetchAiPrediction(String horizon, Long userId) {
        HorizonSpec spec = horizonSpec(horizon);
        var ohlcv = marketData.getOhlcvForUser(userId, spec.period(), spec.interval());
        var indiaVixHistory = marketData.getIndiaVixHistory();
        LiveTickData liveTick = latestPrimaryTick(userId);

        int minBars = minBarsForHorizon(horizon);
        int actualBars = ohlcv != null ? ohlcv.size() : 0;
        if (actualBars < minBars) {
            log.warn("AI fetch userId={} horizon={} insufficient bars (have={} required={}); falling back to last stored prediction",
                    userId, horizon, actualBars, minBars);
            return dbFallbackOrThrow(horizon,
                    new MlServiceUnavailableException(
                            "Insufficient OHLCV bars for horizon " + horizon + " (have=" + actualBars + " required=" + minBars + ")",
                            null));
        }

        AppProperties.Transport transport = appProperties.getMlService().getTransport();
        boolean restConfigured = mlRest.isConfigured();

        // REST-only deployments (e.g. Render free tier) — skip gRPC entirely so we don't spam 404s.
        if (transport == AppProperties.Transport.REST) {
            if (!restConfigured) {
                throw new MlServiceUnavailableException(
                        "ML transport is REST but ML_SERVICE_HTTP_URL is not configured", null);
            }
            log.debug("prediction=AI (fetch, REST) userId={} horizon={}", userId, horizon);
            try {
                return mlRest.predict("AI", horizon, ohlcv, indiaVixHistory, liveTick, userId);
            } catch (Exception restErr) {
                log.warn("REST prediction failed: {}", restErr.getMessage());
                return dbFallbackOrThrow(horizon, restErr);
            }
        }

        try {
            log.debug("prediction=AI (fetch) userId={} horizon={}", userId, horizon);
            return ml.getGeminiPrediction(horizon, ohlcv, indiaVixHistory, liveTick, userId);
        } catch (Exception grpcErr) {
            // GRPC strict mode: rethrow without REST fallback.
            if (transport == AppProperties.Transport.GRPC) {
                log.warn("gRPC prediction failed (GRPC-only mode, no fallback): {}", grpcErr.getMessage());
                return dbFallbackOrThrow(horizon, grpcErr);
            }
            log.warn("gRPC prediction failed, trying HTTP REST fallback: {}", grpcErr.getMessage());

            if (restConfigured) {
                try {
                    return mlRest.predict("AI", horizon, ohlcv, indiaVixHistory, liveTick, userId);
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
