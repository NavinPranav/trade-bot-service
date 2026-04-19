package com.sensex.optiontrader.controller;
import com.sensex.optiontrader.model.dto.request.PositionRequest;
import com.sensex.optiontrader.security.UserPrincipal;
import com.sensex.optiontrader.service.PortfolioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/portfolio") @RequiredArgsConstructor
public class PortfolioController {
    private final PortfolioService svc;
    @GetMapping("/positions") public ResponseEntity<?> positions(@AuthenticationPrincipal UserPrincipal u) { return ResponseEntity.ok(svc.getPositions(u.getId())); }
    @PostMapping("/positions") public ResponseEntity<?> add(@AuthenticationPrincipal UserPrincipal u, @Valid @RequestBody PositionRequest r) { return ResponseEntity.status(HttpStatus.CREATED).body(svc.addPosition(u.getId(), r)); }
    @PutMapping("/positions/{id}/close") public ResponseEntity<?> close(@AuthenticationPrincipal UserPrincipal u, @PathVariable Long id, @RequestParam java.math.BigDecimal exitPrice) { return ResponseEntity.ok(svc.closePosition(u.getId(), id, exitPrice)); }
    @GetMapping("/greeks") public ResponseEntity<?> greeks(@AuthenticationPrincipal UserPrincipal u) { return ResponseEntity.ok(svc.getAggregateGreeks(u.getId())); }
    @GetMapping("/pnl") public ResponseEntity<?> pnl(@AuthenticationPrincipal UserPrincipal u) { return ResponseEntity.ok(svc.getPnl(u.getId())); }
}