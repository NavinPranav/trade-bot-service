package com.sensex.optiontrader.controller;
import com.sensex.optiontrader.model.dto.request.AlertRequest;
import com.sensex.optiontrader.security.UserPrincipal;
import com.sensex.optiontrader.service.AlertService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/alerts") @RequiredArgsConstructor
public class AlertController {
    private final AlertService svc;
    @GetMapping public ResponseEntity<?> list(@AuthenticationPrincipal UserPrincipal u) { return ResponseEntity.ok(svc.getActiveAlerts(u.getId())); }
    @PostMapping public ResponseEntity<?> create(@AuthenticationPrincipal UserPrincipal u, @Valid @RequestBody AlertRequest r) { return ResponseEntity.status(HttpStatus.CREATED).body(svc.createAlert(u.getId(), r)); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal u, @PathVariable Long id) { svc.deleteAlert(u.getId(), id); return ResponseEntity.noContent().build(); }
}