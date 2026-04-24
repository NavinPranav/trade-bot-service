package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.security.UserPrincipal;
import com.sensex.optiontrader.service.StrategyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
public class StrategyController {
    private final StrategyService svc;

    @GetMapping("/recommend")
    public ResponseEntity<?> recommend(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(svc.getRecommendation(principal.getId()));
    }

    @PostMapping("/payoff")
    public ResponseEntity<?> payoff(@RequestBody Map<String, Object> legs) {
        return ResponseEntity.ok(svc.calculatePayoff(legs));
    }

    @GetMapping("/templates")
    public ResponseEntity<?> templates() {
        return ResponseEntity.ok(svc.getTemplates());
    }
}
