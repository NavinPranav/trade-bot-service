package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.model.entity.Instrument;
import com.sensex.optiontrader.service.InstrumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/instruments")
@RequiredArgsConstructor
public class InstrumentController {

    private final InstrumentService service;

    @GetMapping
    public ResponseEntity<List<Instrument>> getAll(
            @RequestParam(required = false) String marketType) {
        List<Instrument> instruments = (marketType != null && !marketType.isBlank())
                ? service.getByMarketType(marketType)
                : service.getAllInstruments();
        return ResponseEntity.ok(instruments);
    }

    @GetMapping("/active")
    public ResponseEntity<List<Instrument>> getActive() {
        return ResponseEntity.ok(service.getActiveInstruments());
    }

    @PostMapping("/{id}/switch")
    public ResponseEntity<Map<String, Object>> switchActive(@PathVariable Long id) {
        Instrument inst = service.switchActive(id);
        return ResponseEntity.ok(Map.of(
                "message", "Switched active instrument to " + inst.getDisplayName(),
                "instrument", inst));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<Map<String, Object>> activate(@PathVariable Long id) {
        Instrument inst = service.activate(id);
        return ResponseEntity.ok(Map.of(
                "message", "Activated " + inst.getDisplayName(),
                "instrument", inst));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Map<String, Object>> deactivate(@PathVariable Long id) {
        Instrument inst = service.deactivate(id);
        return ResponseEntity.ok(Map.of(
                "message", "Deactivated " + inst.getDisplayName(),
                "instrument", inst));
    }
}
