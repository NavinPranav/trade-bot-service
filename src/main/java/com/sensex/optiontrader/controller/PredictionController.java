package com.sensex.optiontrader.controller;
import com.sensex.optiontrader.service.PredictionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/predictions") @RequiredArgsConstructor
public class PredictionController {
    private final PredictionService svc;
    @GetMapping("/latest") public ResponseEntity<?> latest(@RequestParam(defaultValue="1D") String horizon) { return ResponseEntity.ok(svc.getLatestPrediction(horizon)); }
    @GetMapping("/history") public ResponseEntity<?> history(@RequestParam(defaultValue="30") int days, @RequestParam(defaultValue="1D") String horizon) { return ResponseEntity.ok(svc.getPredictionHistory(days, horizon)); }
    @GetMapping("/accuracy") public ResponseEntity<?> accuracy() { return ResponseEntity.ok(svc.getAccuracyMetrics()); }
}