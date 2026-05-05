package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.model.dto.request.UserRiskSettingsRequest;
import com.sensex.optiontrader.model.dto.response.UserRiskSettingsResponse;
import com.sensex.optiontrader.security.UserPrincipal;
import com.sensex.optiontrader.service.UserRiskSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/me/risk-settings")
@RequiredArgsConstructor
public class UserRiskSettingsController {

    private final UserRiskSettingsService userRiskSettingsService;

    @GetMapping
    public ResponseEntity<UserRiskSettingsResponse> get(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(userRiskSettingsService.getForUser(principal.getId()));
    }

    @PutMapping
    public ResponseEntity<?> put(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody UserRiskSettingsRequest body) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "body required"));
        }
        try {
            return ResponseEntity.ok(userRiskSettingsService.update(principal.getId(), body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
