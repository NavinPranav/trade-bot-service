package com.sensex.optiontrader.service;

import com.sensex.optiontrader.exception.MlServiceUnavailableException;
import com.sensex.optiontrader.grpc.MlRestClient;
import com.sensex.optiontrader.grpc.MlServiceClient;
import com.sensex.optiontrader.integration.MarketDataProvider;
import com.sensex.optiontrader.integration.angelone.LiveTickData;
import com.sensex.optiontrader.model.dto.response.PredictionResponse;
import com.sensex.optiontrader.repository.PredictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final PredictionRepository repo;
    private final MlServiceClient ml;
    private final MlRestClient mlRest;
    private final MarketDataService marketData;
    private final MarketDataProvider marketDataProvider;
    private final InstrumentRegistry instrumentRegistry;

    private record HorizonSpec(String period, String interval) {}

    /** Maps prediction horizon to historical data period + bar interval for the Angel One API. */
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

    /** AI (Gemini) prediction only; scoped per user and preferred instrument. */
    @Cacheable(
            value = "predictions",
            key = "'v8-AI-'+#horizon+'-u'+#userId+'-tok'+@instrumentRegistry.primaryTokenOrNone(#userId)"
    )
    public PredictionResponse getLatestPrediction(String horizon, Long userId) {
        HorizonSpec spec = horizonSpec(horizon);
        var ohlcv = marketData.getOhlcvForUser(userId, spec.period(), spec.interval());
        var indiaVixHistory = marketData.getIndiaVixHistory();
        LiveTickData liveTick = latestPrimaryTick(userId);

        try {
            log.debug("prediction=AI (Gemini) userId={} horizon={}", userId, horizon);
            return ml.getGeminiPrediction(horizon, ohlcv, indiaVixHistory, liveTick, userId);
        } catch (Exception grpcErr) {
            log.warn("gRPC prediction failed, trying HTTP REST fallback: {}", grpcErr.getMessage());

            if (mlRest.isConfigured()) {
                try {
                    return mlRest.predict("AI", horizon, ohlcv, indiaVixHistory, liveTick, userId);
                } catch (Exception restErr) {
                    log.warn("HTTP REST prediction also failed: {}", restErr.getMessage());
                }
            }

            var dbFallback = repo.findTopByHorizonOrderByPredictionDateDesc(horizon);
            if (dbFallback.isPresent()) {
                var p = dbFallback.get();
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
                        .build();
            }
            throw new MlServiceUnavailableException(
                    "ML service unreachable and no cached predictions for horizon: " + horizon, grpcErr);
        }
    }

    public List<PredictionResponse> getPredictionHistory(int days, String horizon) {
        var end = LocalDate.now();
        return repo.findByPredictionDateBetweenOrderByPredictionDateDesc(end.minusDays(days), end).stream()
                .filter(p -> p.getHorizon().equals(horizon))
                .map(p -> PredictionResponse.builder()
                        .predictionDate(p.getPredictionDate())
                        .horizon(p.getHorizon())
                        .direction(p.getDirection())
                        .magnitude(p.getMagnitude())
                        .confidence(p.getConfidence())
                        .build())
                .collect(Collectors.toList());
    }

    public Map<String, Object> getAccuracyMetrics() {
        var m = new HashMap<String, Object>();
        for (String h : List.of("1D", "3D", "1W")) {
            long c = repo.countCorrectByHorizon(h);
            long t = repo.countEvaluatedByHorizon(h);
            m.put(h, Map.of("correct", c, "total", t, "accuracy", t > 0 ? (double) c / t * 100 : 0));
        }
        return m;
    }

    private LiveTickData latestPrimaryTick(Long userId) {
        return instrumentRegistry.getPrimaryForUser(userId)
                .map(i -> marketDataProvider.getLatestTick(i.token()))
                .orElse(null);
    }
}
