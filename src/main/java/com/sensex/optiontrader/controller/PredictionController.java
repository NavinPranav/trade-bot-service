package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.model.enums.PredictionHistoryScope;
import com.sensex.optiontrader.security.UserPrincipal;
import com.sensex.optiontrader.service.PredictionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/predictions")
@RequiredArgsConstructor
public class PredictionController {

    private final PredictionService svc;

    @GetMapping("/latest")
    public ResponseEntity<?> latest(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "1D") String horizon) {
        return ResponseEntity.ok(svc.getLatestPrediction(horizon, principal.getId()));
    }

    /**
     * Paginated prediction history with outcome metrics.
     * Query params: {@code horizons} and {@code signals} repeatable (e.g. {@code horizons=5M&horizons=15M});
     * omitted or empty = no filter on that axis. page (default 0), size (default 20),
     * scope {@code all} — entire platform (admins only; non-admins are treated as {@code mine});
     * {@code mine} — authenticated user only.
     * Response echoes applied {@code horizons} and {@code signals} lists.
     * {@code sortTime} {@code asc} | {@code desc} — order by prediction timestamp (default {@code desc}).
     */
    @GetMapping("/history")
    public ResponseEntity<?> history(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) List<String> horizons,
            @RequestParam(required = false) List<String> signals,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "desc") String sortTime) {
        PredictionHistoryScope sc = PredictionHistoryScope.fromQueryParam(scope);
        if (sc == PredictionHistoryScope.ALL && !principal.isAdmin()) {
            sc = PredictionHistoryScope.MINE;
        }
        return ResponseEntity.ok(svc.getPredictionHistory(principal.getId(), horizons, signals, page, size, sc, sortTime));
    }

    @GetMapping("/accuracy")
    public ResponseEntity<?> accuracy(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(svc.getAccuracyMetrics());
    }

    @PostMapping("/analyse")
    public ResponseEntity<?> analyse(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> rawIds = (List<Number>) body.get("predictionIds");
        if (rawIds == null || rawIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "predictionIds is required and must not be empty"));
        }
        List<Long> ids = rawIds.stream().map(Number::longValue).toList();
        return ResponseEntity.ok(svc.analysePredictions(ids, principal.getId(), principal.isAdmin()));
    }
}
