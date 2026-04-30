package com.sensex.optiontrader.controller;
import com.sensex.optiontrader.model.dto.request.*;
import com.sensex.optiontrader.model.dto.response.AuthResponse;
import com.sensex.optiontrader.model.dto.response.CurrentUserResponse;
import com.sensex.optiontrader.security.UserPrincipal;
import com.sensex.optiontrader.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/auth") @RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @GetMapping("/me")
    public ResponseEntity<CurrentUserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(authService.getCurrentUser(principal.getId()));
    }
    @PostMapping("/register") public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest r) { return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(r)); }
    /** First admin only; requires {@code X-Admin-Bootstrap-Secret} and {@code ADMIN_BOOTSTRAP_SECRET} on the server. */
    @PostMapping("/bootstrap-admin")
    public ResponseEntity<AuthResponse> bootstrapAdmin(
            @RequestHeader("X-Admin-Bootstrap-Secret") String bootstrapSecret,
            @Valid @RequestBody RegisterRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.bootstrapAdmin(r, bootstrapSecret));
    }
    @PostMapping("/login") public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest r) { return ResponseEntity.ok(authService.login(r)); }
    @PostMapping("/refresh") public ResponseEntity<AuthResponse> refresh(@RequestHeader("X-Refresh-Token") String t) { return ResponseEntity.ok(authService.refresh(t)); }
}