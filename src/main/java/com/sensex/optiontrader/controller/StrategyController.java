package com.sensex.optiontrader.controller;
import com.sensex.optiontrader.service.StrategyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/strategy") @RequiredArgsConstructor
public class StrategyController {
    private final StrategyService svc;
    @GetMapping("/recommend") public ResponseEntity<?> recommend() { return ResponseEntity.ok(svc.getRecommendation()); }
    @PostMapping("/payoff") public ResponseEntity<?> payoff(@RequestBody Map<String,Object> legs) { return ResponseEntity.ok(svc.calculatePayoff(legs)); }
    @GetMapping("/templates") public ResponseEntity<?> templates() { return ResponseEntity.ok(svc.getTemplates()); }
}