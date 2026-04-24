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

    @GetMapping("/history")
    public ResponseEntity<?> history(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "1D") String horizon) {
        return ResponseEntity.ok(svc.getPredictionHistory(days, horizon));
    }

    @GetMapping("/accuracy")
    public ResponseEntity<?> accuracy(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(svc.getAccuracyMetrics());
    }
}
