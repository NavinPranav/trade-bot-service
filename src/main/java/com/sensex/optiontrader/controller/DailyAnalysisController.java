package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.security.UserPrincipal;
import com.sensex.optiontrader.service.DailyAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/predictions/daily-analysis")
@RequiredArgsConstructor
public class DailyAnalysisController {

    private final DailyAnalysisService svc;

    /** Returns the latest daily analysis if the user has not yet dismissed it; 204 if none. */
    @GetMapping("/latest")
    public ResponseEntity<?> latest(@AuthenticationPrincipal UserPrincipal principal) {
        return svc.getUnreadForUser(principal.getId())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /** Marks a daily analysis as read for the authenticated user. */
    @PostMapping("/{id}/read")
    public ResponseEntity<?> markRead(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        svc.markRead(id, principal.getId());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Admin-only: manually trigger today's daily analysis (useful for testing). */
    @PostMapping("/trigger")
    public ResponseEntity<?> trigger(@AuthenticationPrincipal UserPrincipal principal) {
        if (!principal.isAdmin()) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin only"));
        }
        svc.runDailyAnalysis();
        return ResponseEntity.ok(Map.of("triggered", true));
    }
}
