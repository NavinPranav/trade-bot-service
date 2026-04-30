package com.sensex.optiontrader.service;

import com.sensex.optiontrader.exception.MlServiceUnavailableException;
import com.sensex.optiontrader.grpc.MlRestClient;
import com.sensex.optiontrader.grpc.MlServiceClient;
import com.sensex.optiontrader.integration.MarketDataProvider;
import com.sensex.optiontrader.integration.angelone.LiveTickData;
import com.sensex.optiontrader.model.dto.response.PredictionHistoryResponse;
import com.sensex.optiontrader.model.dto.response.PredictionResponse;
import com.sensex.optiontrader.model.entity.Prediction;
import com.sensex.optiontrader.repository.PredictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Returns paginated prediction history for the table view, plus aggregate metrics
     * for the summary banner — all in a single response to avoid a second round-trip.
     */
    public Map<String, Object> getPredictionHistory(Long userId, String horizon, int page, int size) {
        String h = (horizon == null || horizon.isBlank()) ? null : horizon.trim().toUpperCase();

        Page<Prediction> pageResult = repo.findHistoryByUser(
                userId, h,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "predictionTimestamp")));

        List<PredictionHistoryResponse> rows = pageResult.getContent().stream()
                .map(this::toHistoryResponse)
                .toList();

        Map<String, Object> summary = buildSummary(userId, h);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("predictions", rows);
        response.put("page", pageResult.getNumber());
        response.put("size", pageResult.getSize());
        response.put("total", pageResult.getTotalElements());
        response.put("totalPages", pageResult.getTotalPages());
        response.put("summary", summary);
        return response;
    }

    private PredictionHistoryResponse toHistoryResponse(Prediction p) {
        return PredictionHistoryResponse.builder()
                .id(p.getId())
                .predictionDate(p.getPredictionDate())
                .predictionTimestamp(p.getPredictionTimestamp())
                .horizon(p.getHorizon())
                .direction(p.getDirection() != null ? p.getDirection().name() : null)
                .confidence(p.getConfidence())
                .predictedVolatility(p.getPredictedVolatility())
                .currentSensex(p.getCurrentSensex())
                .entryPrice(p.getEntryPrice())
                .stopLoss(p.getStopLoss())
                .targetSensex(p.getTargetSensex())
                .riskReward(p.getRiskReward())
                .noTradeZone(p.getNoTradeZone())
                .outcomeStatus(p.getOutcomeStatus() != null ? p.getOutcomeStatus().name() : null)
                .actualClosePrice(p.getActualClosePrice())
                .actualHighPrice(p.getActualHighPrice())
                .actualLowPrice(p.getActualLowPrice())
                .targetHit(p.getTargetHit())
                .stopLossHit(p.getStopLossHit())
                .actualPnlPct(p.getActualPnlPct())
                .predictionReason(p.getDetail() != null ? p.getDetail().getPredictionReason() : null)
                .aiTool(p.getAiTool())
                .aiModel(p.getAiModel())
                .build();
    }

    private Map<String, Object> buildSummary(Long userId, String horizon) {
        long total = repo.countByUserAndHorizon(userId, horizon);
        long resolved = repo.countResolvedByUserAndHorizon(userId, horizon);
        long targetHits = repo.countTargetHitsByUserAndHorizon(userId, horizon);
        long slHits = repo.countStopLossHitsByUserAndHorizon(userId, horizon);
        Double avgConf = repo.avgConfidenceByUserAndHorizon(userId, horizon);
        Double avgRr = repo.avgRiskRewardByUserAndHorizon(userId, horizon);

        double winRate = resolved > 0 ? (double) targetHits / resolved * 100 : 0;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("resolved", resolved);
        m.put("pending", total - resolved);
        m.put("targetHits", targetHits);
        m.put("stopLossHits", slHits);
        m.put("expired", resolved - targetHits - slHits);
        m.put("winRatePct", Math.round(winRate * 10.0) / 10.0);
        m.put("avgConfidence", avgConf != null ? Math.round(avgConf * 10.0) / 10.0 : null);
        m.put("avgRiskReward", avgRr != null ? Math.round(avgRr * 100.0) / 100.0 : null);
        return m;
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
