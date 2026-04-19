package com.sensex.optiontrader.service;

import com.sensex.optiontrader.config.AngelOneProperties;
import com.sensex.optiontrader.exception.MlServiceUnavailableException;
import com.sensex.optiontrader.exception.ResourceNotFoundException;
import com.sensex.optiontrader.grpc.MlServiceClient;
import com.sensex.optiontrader.integration.MarketDataProvider;
import com.sensex.optiontrader.integration.angelone.LiveTickData;
import com.sensex.optiontrader.model.dto.response.PredictionResponse;
import com.sensex.optiontrader.repository.MlServiceConfigRepository;
import com.sensex.optiontrader.repository.PredictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionService {
    private final PredictionRepository repo;
    private final MlServiceClient ml;
    private final MarketDataService marketData;
    private final MarketDataProvider marketDataProvider;
    private final AngelOneProperties angelOneProps;
    private final MlServiceConfigRepository configRepo;

    private record HorizonSpec(String period, String interval) {}

    /** Maps prediction horizon to historical data period + bar interval for the Angel One API. */
    private static HorizonSpec horizonSpec(String horizon) {
        String h = horizon == null ? "1D" : horizon.trim().toUpperCase();
        return switch (h) {
            case "1D" -> new HorizonSpec("1Y", "1D");
            case "3D" -> new HorizonSpec("1Y", "1D");
            case "1W" -> new HorizonSpec("2Y", "1D");
            case "1H" -> new HorizonSpec("1M", "5M");
            default -> new HorizonSpec("1Y", "1D");
        };
    }

    @Cacheable(value = "predictions", key = "'v5-'+#horizon")
    public PredictionResponse getLatestPrediction(String horizon) {
        Exception mlError = null;
        try {
            HorizonSpec spec = horizonSpec(horizon);
            var ohlcv = marketData.getSensexOhlcv(spec.period(), spec.interval());
            var indiaVixHistory = marketData.getIndiaVixHistory();
            LiveTickData liveTick = latestPrimaryTick();

            if (isAiEngine()) {
                log.debug("prediction_engine=AI → routing to GetGeminiPrediction");
                return ml.getGeminiPrediction(horizon, ohlcv, indiaVixHistory, liveTick);
            }
            return ml.getPrediction(horizon, ohlcv, indiaVixHistory, liveTick);
        } catch (Exception e) {
            log.warn("ML service call failed, trying DB fallback: {}", e.getMessage());
            mlError = e;
        }
        var dbFallback = repo.findTopByHorizonOrderByPredictionDateDesc(horizon);
        if (dbFallback.isPresent()) {
            var p = dbFallback.get();
            return PredictionResponse.builder()
                    .predictionDate(p.getPredictionDate())
                    .horizon(p.getHorizon())
                    .direction(p.getDirection())
                    .magnitude(p.getMagnitude())
                    .confidence(p.getConfidence())
                    .predictedVolatility(p.getPredictedVolatility())
                    .build();
        }
        throw new MlServiceUnavailableException(
                "ML service unreachable and no cached predictions for horizon: " + horizon, mlError);
    }

    /** Returns true when the ml_service_config table has prediction_engine=AI. */
    public boolean isAiEngine() {
        return configRepo.findByConfigKey("prediction_engine")
                .map(c -> "AI".equalsIgnoreCase(c.getConfigValue()))
                .orElse(false);
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

    /** Returns the latest live tick for the primary instrument (e.g. SENSEX), or null if streaming is idle. */
    private LiveTickData latestPrimaryTick() {
        if (angelOneProps.instruments() == null || angelOneProps.instruments().isEmpty()) {
            return null;
        }
        String primaryToken = angelOneProps.instruments().get(0).token();
        return marketDataProvider.getLatestTick(primaryToken);
    }
}
