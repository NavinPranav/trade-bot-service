package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shorthand paths for market data ({@code /api/sensex/...}) in addition to {@link MarketDataController}
 * ({@code /api/market/...}).
 */
@RestController
@RequestMapping("/api/sensex")
@RequiredArgsConstructor
public class SensexAliasController {
    private final MarketDataService svc;

    @GetMapping("/ohlcv")
    public ResponseEntity<?> ohlcv(
            @RequestParam(defaultValue = "1Y") String period, @RequestParam(defaultValue = "1D") String interval) {
        return ResponseEntity.ok(svc.getSensexOhlcv(period, interval));
    }

    @GetMapping("/vix")
    public ResponseEntity<?> vix() {
        return ResponseEntity.ok(svc.getCurrentVix());
    }

    @GetMapping("/fii-dii")
    public ResponseEntity<?> fiiDii(@RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(svc.getFiiDiiActivity(days));
    }
}
