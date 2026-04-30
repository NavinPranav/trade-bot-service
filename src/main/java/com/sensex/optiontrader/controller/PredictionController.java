package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.security.UserPrincipal;
import com.sensex.optiontrader.service.PredictionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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
     * Query params: horizon (optional), page (default 0), size (default 20).
     * Response includes a summary object with aggregate win-rate, avg confidence, etc.
     */
    @GetMapping("/history")
    public ResponseEntity<?> history(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String horizon,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(svc.getPredictionHistory(principal.getId(), horizon, page, size));
    }

    @GetMapping("/accuracy")
    public ResponseEntity<?> accuracy(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(svc.getAccuracyMetrics());
    }
}
