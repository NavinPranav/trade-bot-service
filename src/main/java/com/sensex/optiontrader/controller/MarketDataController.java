package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.security.UserPrincipal;
import com.sensex.optiontrader.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketDataController {
    private final MarketDataService svc;

    @GetMapping("/sensex/ohlcv")
    public ResponseEntity<?> ohlcv(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "1Y") String period,
            @RequestParam(defaultValue = "1D") String interval) {
        return ResponseEntity.ok(svc.getOhlcvForUser(principal.getId(), period, interval));
    }

    @GetMapping("/vix")
    public ResponseEntity<?> vix(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(svc.getCurrentVix());
    }

    @GetMapping("/fii-dii")
    public ResponseEntity<?> fiiDii(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(svc.getFiiDiiActivity(days));
    }
}
