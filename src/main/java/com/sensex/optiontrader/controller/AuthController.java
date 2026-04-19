package com.sensex.optiontrader.controller;
import com.sensex.optiontrader.model.dto.request.*;
import com.sensex.optiontrader.model.dto.response.AuthResponse;
import com.sensex.optiontrader.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/auth") @RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    @PostMapping("/register") public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest r) { return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(r)); }
    @PostMapping("/login") public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest r) { return ResponseEntity.ok(authService.login(r)); }
    @PostMapping("/refresh") public ResponseEntity<AuthResponse> refresh(@RequestHeader("X-Refresh-Token") String t) { return ResponseEntity.ok(authService.refresh(t)); }
}