package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.grpc.MlRestClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/prediction-policy")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminPredictionPolicyController {

    private final MlRestClient mlRestClient;

    @GetMapping
    public ResponseEntity<?> getPolicy() {
        try {
            return ResponseEntity.ok(mlRestClient.getPredictionPolicy());
        } catch (Exception e) {
            log.error("Failed to get prediction policy from ML service: {}", e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping
    public ResponseEntity<?> setPolicy(@RequestBody Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "body is required"));
        }
        try {
            return ResponseEntity.ok(mlRestClient.setPredictionPolicy(body));
        } catch (Exception e) {
            log.error("Failed to set prediction policy on ML service: {}", e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping
    public ResponseEntity<?> resetPolicy() {
        try {
            return ResponseEntity.ok(mlRestClient.resetPredictionPolicy());
        } catch (Exception e) {
            log.error("Failed to reset prediction policy on ML service: {}", e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }
}
