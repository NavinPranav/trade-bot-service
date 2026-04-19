package com.sensex.optiontrader.controller;
import com.sensex.optiontrader.model.dto.request.BacktestRequest;
import com.sensex.optiontrader.security.UserPrincipal;
import com.sensex.optiontrader.service.BacktestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/backtest") @RequiredArgsConstructor
public class BacktestController {
    private final BacktestService svc;
    @PostMapping("/run") public ResponseEntity<?> run(@AuthenticationPrincipal UserPrincipal u, @Valid @RequestBody BacktestRequest r) { return ResponseEntity.status(HttpStatus.ACCEPTED).body(svc.submitBacktest(u.getId(), r)); }
    @GetMapping("/{id}/status") public ResponseEntity<?> status(@PathVariable Long id) { return ResponseEntity.ok(svc.getStatus(id)); }
    @GetMapping("/{id}/results") public ResponseEntity<?> results(@PathVariable Long id) { return ResponseEntity.ok(svc.getResults(id)); }
}