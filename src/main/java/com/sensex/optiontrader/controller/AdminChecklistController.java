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
@RequestMapping("/api/admin/checklist-weight")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminChecklistController {

    private final MlRestClient mlRestClient;

    @GetMapping
    public ResponseEntity<?> getWeight() {
        try {
            Map<String, Object> result = mlRestClient.getChecklistWeight();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to get checklist weight from ML service: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("weight", 40, "remaining", 60));
        }
    }

    @PutMapping
    public ResponseEntity<?> setWeight(@RequestBody Map<String, Object> body) {
        Object raw = body.get("weight");
        if (raw == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "weight is required"));
        }
        int weight;
        try {
            weight = Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "weight must be an integer 0–100"));
        }
        if (weight < 0 || weight > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "weight must be between 0 and 100"));
        }
        try {
            Map<String, Object> result = mlRestClient.setChecklistWeight(weight);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to set checklist weight on ML service: {}", e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }
}
