package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.model.entity.Prediction;
import com.sensex.optiontrader.model.enums.Direction;
import com.sensex.optiontrader.repository.PredictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Read-only admin endpoint that exports resolved directional predictions in
 * the shape the ML service expects for confidence calibration (Phase 4.3).
 *
 * Workflow:
 *   1. Operator hits this endpoint to fetch a JSON list of {confidence, win, …}.
 *   2. POSTs that JSON to the ML service ``/admin/calibration/refit`` (or lets
 *      the ML service pull via ``backend_url`` + ``backend_token``).
 *   3. The ML service fits an isotonic map and starts applying it before the
 *      confidence floor.
 *
 * "Win" = (target_hit == true) OR (actual_pnl_pct > 0). HOLD and other
 * non-directional rows are excluded server-side.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/predictions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminPredictionsCalibrationController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final List<Direction> DIRECTIONAL = List.of(Direction.BUY, Direction.SELL);

    private final PredictionRepository predictionRepository;

    @GetMapping("/calibration-data")
    public ResponseEntity<?> calibrationData(
            @RequestParam(required = false) String horizon,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "2000") int limit) {

        int safeDays = Math.max(1, Math.min(days, 365));
        int safeLimit = Math.max(1, Math.min(limit, 10_000));
        boolean horizonAll = horizon == null || horizon.isBlank();
        String horizonValue = horizonAll ? "" : horizon.trim();
        LocalDate since = LocalDate.now(IST).minusDays(safeDays);

        List<Prediction> rows = predictionRepository.findResolvedForCalibration(
                DIRECTIONAL,
                since,
                horizonAll,
                horizonValue,
                PageRequest.of(0, safeLimit));

        List<Map<String, Object>> samples = new ArrayList<>(rows.size());
        int wins = 0;
        for (Prediction p : rows) {
            BigDecimal conf = p.getConfidence();
            if (conf == null) continue;
            // Win semantics: explicit target hit, otherwise sign of resolved P&L%.
            boolean win = Boolean.TRUE.equals(p.getTargetHit())
                    || (p.getActualPnlPct() != null && p.getActualPnlPct().signum() > 0);
            if (win) wins++;

            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("confidence", conf.doubleValue());
            sample.put("win", win);
            sample.put("direction", p.getDirection() != null ? p.getDirection().name() : null);
            sample.put("horizon", p.getHorizon());
            sample.put("predictionDate",
                    p.getPredictionDate() != null ? p.getPredictionDate().toString() : null);
            sample.put("targetHit", p.getTargetHit());
            sample.put("stopLossHit", p.getStopLossHit());
            sample.put("actualPnlPct",
                    p.getActualPnlPct() != null ? p.getActualPnlPct().doubleValue() : null);
            sample.put("outcomeStatus",
                    p.getOutcomeStatus() != null ? p.getOutcomeStatus().name() : null);
            samples.add(sample);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", samples.size());
        response.put("wins", wins);
        response.put("winRatePct", samples.isEmpty()
                ? null : Math.round(((double) wins / samples.size()) * 10000.0) / 100.0);
        response.put("sinceDate", since.toString());
        response.put("horizon", horizonAll ? "ALL" : horizonValue);
        response.put("limit", safeLimit);
        response.put("samples", samples);

        log.info("calibration-data export: count={} wins={} since={} horizon={}",
                samples.size(), wins, since, horizonAll ? "ALL" : horizonValue);

        return ResponseEntity.ok(response);
    }
}
