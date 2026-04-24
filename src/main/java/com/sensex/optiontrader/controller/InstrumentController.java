package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.model.entity.Instrument;
import com.sensex.optiontrader.security.UserPrincipal;
import com.sensex.optiontrader.service.InstrumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/instruments")
@RequiredArgsConstructor
public class InstrumentController {

    private final InstrumentService service;

    @GetMapping
    public ResponseEntity<List<Instrument>> getAll(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String marketType) {
        Objects.requireNonNull(principal, "Authentication required");
        List<Instrument> instruments = (marketType != null && !marketType.isBlank())
                ? service.getByMarketType(marketType)
                : service.getAllInstruments();
        return ResponseEntity.ok(instruments);
    }

    @GetMapping("/active")
    public ResponseEntity<List<Instrument>> getActive(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(service.getActiveInstrumentsForUser(principal.getId()));
    }

    @PostMapping("/{id}/switch")
    public ResponseEntity<Map<String, Object>> switchActive(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        Instrument inst = service.switchActive(principal.getId(), id);
        return ResponseEntity.ok(Map.of(
                "message", "Switched preferred instrument to " + inst.getDisplayName(),
                "instrument", inst));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<Map<String, Object>> activate(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        Instrument inst = service.activate(principal.getId(), id);
        return ResponseEntity.ok(Map.of(
                "message", "Activated " + inst.getDisplayName(),
                "instrument", inst));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Map<String, Object>> deactivate(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        Instrument inst = service.deactivate(principal.getId(), id);
        return ResponseEntity.ok(Map.of(
                "message", "Deactivated " + inst.getDisplayName(),
                "instrument", inst));
    }
}
