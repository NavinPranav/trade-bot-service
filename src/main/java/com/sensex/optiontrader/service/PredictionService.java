package com.sensex.optiontrader.service;

import com.sensex.optiontrader.exception.MlServiceUnavailableException;
import com.sensex.optiontrader.grpc.MlRestClient;
import com.sensex.optiontrader.grpc.MlServiceClient;
import com.sensex.optiontrader.integration.MarketDataProvider;
import com.sensex.optiontrader.integration.angelone.LiveTickData;
import com.sensex.optiontrader.model.dto.response.PredictionHistoryResponse;
import com.sensex.optiontrader.model.dto.response.PredictionResponse;
import com.sensex.optiontrader.model.entity.Prediction;
import com.sensex.optiontrader.model.enums.Direction;
import com.sensex.optiontrader.model.enums.PredictionHistoryScope;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Horizons exposed in history UI / websocket filters; IN clause dummy when “all”. */
    private static final Set<String> HISTORY_HORIZON_CODES = Set.of("5M", "15M", "30M");

    private record HistoryFilters(
            boolean horizonAll,
            List<String> horizonsForQuery,
            boolean directionAll,
            List<Direction> directionsForQuery,
            List<String> horizonsEcho,
            List<String> signalsEcho) {}

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
     *
     * @param scope {@link PredictionHistoryScope#ALL} for every stored prediction; {@link PredictionHistoryScope#MINE} for the caller only.
     * @param horizons optional repeated query params (e.g. 5M, 15M); empty or omitted = no horizon filter.
     * @param signals optional direction names (BUY, SELL, …); empty or omitted = no signal filter.
     */
    public Map<String, Object> getPredictionHistory(
            Long userId,
            Collection<String> horizons,
            Collection<String> signals,
            int page,
            int size,
            PredictionHistoryScope scope,
            String sortTime) {
        HistoryFilters f = resolveHistoryFilters(horizons, signals);
        Sort.Direction timeDir = parseTimeSortDirection(sortTime);

        Page<Prediction> pageResult = scope == PredictionHistoryScope.ALL
                ? repo.findHistoryAll(
                        f.horizonAll(),
                        f.horizonsForQuery(),
                        f.directionAll(),
                        f.directionsForQuery(),
                        PageRequest.of(page, size, Sort.by(timeDir, "predictionTimestamp")))
                : repo.findHistoryByUser(
                        userId,
                        f.horizonAll(),
                        f.horizonsForQuery(),
                        f.directionAll(),
                        f.directionsForQuery(),
                        PageRequest.of(page, size, Sort.by(timeDir, "predictionTimestamp")));

        List<PredictionHistoryResponse> rows = pageResult.getContent().stream()
                .map(this::toHistoryResponse)
                .toList();

        Map<String, Object> summary = scope == PredictionHistoryScope.ALL
                ? buildSummaryAll(f)
                : buildSummary(userId, f);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("predictions", rows);
        response.put("page", pageResult.getNumber());
        response.put("size", pageResult.getSize());
        response.put("total", pageResult.getTotalElements());
        response.put("totalPages", pageResult.getTotalPages());
        response.put("summary", summary);
        response.put("scope", scope.name().toLowerCase());
        response.put("horizons", f.horizonsEcho());
        response.put("signals", f.signalsEcho());
        response.put("sortTime", timeDir == Sort.Direction.ASC ? "asc" : "desc");
        return response;
    }

    static Sort.Direction parseTimeSortDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            return Sort.Direction.DESC;
        }
        String u = raw.trim().toLowerCase();
        if ("asc".equals(u) || "ascending".equals(u)) {
            return Sort.Direction.ASC;
        }
        return Sort.Direction.DESC;
    }

    static HistoryFilters resolveHistoryFilters(Collection<String> horizonsRaw, Collection<String> signalsRaw) {
        List<String> hEcho = parseHorizonCodes(horizonsRaw);
        boolean horizonAll = hEcho.isEmpty();
        List<String> hQuery = horizonAll ? List.of("5M") : hEcho;

        List<Direction> dirEcho = parseDirectionCodes(signalsRaw);
        boolean directionAll = dirEcho.isEmpty();
        List<Direction> dQuery = directionAll ? List.of(Direction.HOLD) : dirEcho;

        List<String> signalsEcho = dirEcho.stream().map(Enum::name).toList();

        return new HistoryFilters(horizonAll, hQuery, directionAll, dQuery, hEcho, signalsEcho);
    }

    private static List<String> parseHorizonCodes(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> acc = new ArrayList<>();
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            for (String part : s.split(",")) {
                String t = part.trim().toUpperCase();
                if (HISTORY_HORIZON_CODES.contains(t)) {
                    acc.add(t);
                }
            }
        }
        return acc.stream().distinct().toList();
    }

    private static List<Direction> parseDirectionCodes(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Direction> acc = new ArrayList<>();
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            for (String part : s.split(",")) {
                String u = part.trim().toUpperCase();
                if ("ALL".equals(u) || "ANY".equals(u)) {
                    continue;
                }
                try {
                    Direction d = Direction.valueOf(u);
                    acc.add(d);
                } catch (IllegalArgumentException ignored) {
                    // skip unknown tokens
                }
            }
        }
        return acc.stream().distinct().toList();
    }

    private PredictionHistoryResponse toHistoryResponse(Prediction p) {
        var b = PredictionHistoryResponse.builder()
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
                .aiModel(p.getAiModel());
        if (p.getUser() != null) {
            b.userId(p.getUser().getId()).userEmail(p.getUser().getEmail());
        }
        return b.build();
    }

    private Map<String, Object> buildSummaryAll(HistoryFilters f) {
        long total = repo.countAllByHorizon(
                f.horizonAll(), f.horizonsForQuery(), f.directionAll(), f.directionsForQuery());
        long resolved = repo.countResolvedAllByHorizon(
                f.horizonAll(), f.horizonsForQuery(), f.directionAll(), f.directionsForQuery());
        long targetHits = repo.countTargetHitsAllByHorizon(
                f.horizonAll(), f.horizonsForQuery(), f.directionAll(), f.directionsForQuery());
        long slHits = repo.countStopLossHitsAllByHorizon(
                f.horizonAll(), f.horizonsForQuery(), f.directionAll(), f.directionsForQuery());
        Double avgConf = repo.avgConfidenceAllByHorizon(
                f.horizonAll(), f.horizonsForQuery(), f.directionAll(), f.directionsForQuery());
        Double avgRr = repo.avgRiskRewardAllByHorizon(
                f.horizonAll(), f.horizonsForQuery(), f.directionAll(), f.directionsForQuery());

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

    private Map<String, Object> buildSummary(Long userId, HistoryFilters f) {
        long total = repo.countByUserAndHorizon(
                userId,
                f.horizonAll(),
                f.horizonsForQuery(),
                f.directionAll(),
                f.directionsForQuery());
        long resolved = repo.countResolvedByUserAndHorizon(
                userId,
                f.horizonAll(),
                f.horizonsForQuery(),
                f.directionAll(),
                f.directionsForQuery());
        long targetHits = repo.countTargetHitsByUserAndHorizon(
                userId,
                f.horizonAll(),
                f.horizonsForQuery(),
                f.directionAll(),
                f.directionsForQuery());
        long slHits = repo.countStopLossHitsByUserAndHorizon(
                userId,
                f.horizonAll(),
                f.horizonsForQuery(),
                f.directionAll(),
                f.directionsForQuery());
        Double avgConf = repo.avgConfidenceByUserAndHorizon(
                userId,
                f.horizonAll(),
                f.horizonsForQuery(),
                f.directionAll(),
                f.directionsForQuery());
        Double avgRr = repo.avgRiskRewardByUserAndHorizon(
                userId,
                f.horizonAll(),
                f.horizonsForQuery(),
                f.directionAll(),
                f.directionsForQuery());

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

    public Map<String, Object> analysePredictions(List<Long> predictionIds, Long userId, boolean admin) {
        List<Prediction> all = repo.findAllById(predictionIds);
        List<Prediction> visible = admin
                ? all
                : all.stream()
                        .filter(p -> p.getUser() != null && p.getUser().getId().equals(userId))
                        .toList();

        if (visible.isEmpty()) {
            return Map.of("error", "No predictions found for the given IDs");
        }

        if (!mlRest.isConfigured()) {
            return Map.of("error", "ML service not configured — cannot run analysis");
        }

        List<Map<String, Object>> predData = visible.stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("predictionDate", p.getPredictionDate() != null ? p.getPredictionDate().toString() : null);
                    m.put("horizon", p.getHorizon());
                    m.put("direction", p.getDirection() != null ? p.getDirection().name() : null);
                    m.put("confidence", p.getConfidence());
                    m.put("entryPrice", p.getEntryPrice());
                    m.put("stopLoss", p.getStopLoss());
                    m.put("targetSensex", p.getTargetSensex());
                    m.put("actualClosePrice", p.getActualClosePrice());
                    m.put("outcomeStatus", p.getOutcomeStatus() != null ? p.getOutcomeStatus().name() : "PENDING");
                    m.put("actualPnlPct", p.getActualPnlPct());
                    m.put("predictionReason", p.getDetail() != null ? p.getDetail().getPredictionReason() : null);
                    m.put("aiTool", p.getAiTool());
                    m.put("aiModel", p.getAiModel());
                    return m;
                })
                .toList();

        return mlRest.analysePredictions(predData);
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
