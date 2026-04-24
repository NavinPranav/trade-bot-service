package com.sensex.optiontrader.service;

import com.sensex.optiontrader.config.AppProperties;
import com.sensex.optiontrader.exception.*;
import com.sensex.optiontrader.model.dto.request.*;
import com.sensex.optiontrader.model.dto.response.AuthResponse;
import com.sensex.optiontrader.model.entity.User;
import com.sensex.optiontrader.repository.InstrumentRepository;
import com.sensex.optiontrader.repository.UserRepository;
import com.sensex.optiontrader.security.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepo;
    private final InstrumentRepository instrumentRepo;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authManager;
    private final JwtTokenProvider tokenProvider;
    private final AppProperties props;

    @Transactional public AuthResponse register(RegisterRequest req) {
        if (userRepo.existsByEmail(req.getEmail())) throw new BadRequestException("Email already registered");
        var bankNifty = instrumentRepo.findByNameIgnoreCase("BANKNIFTY")
                .orElseThrow(() -> new BadRequestException("Default instrument BANKNIFTY is not configured"));
        var user = User.builder()
                .name(req.getName())
                .email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))
                .preferredInstrument(bankNifty)
                .build();
        userRepo.save(user);
        var auth = authManager.authenticate(new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword()));
        return buildAuth(auth, user);
    }
    public AuthResponse login(LoginRequest req) {
        var auth = authManager.authenticate(new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword()));
        var user = userRepo.findByEmail(req.getEmail()).orElseThrow(() -> new UnauthorizedException("Invalid credentials"));
        return buildAuth(auth, user);
    }
    public AuthResponse refresh(String token) {
        if (!tokenProvider.validateToken(token)) throw new UnauthorizedException("Invalid refresh token");
        var user = userRepo.findById(tokenProvider.getUserIdFromToken(token)).orElseThrow(() -> new UnauthorizedException("User not found"));
        return buildAuth(new UsernamePasswordAuthenticationToken(UserPrincipal.from(user), null), user);
    }
    private AuthResponse buildAuth(Authentication auth, User user) {
        return AuthResponse.builder().accessToken(tokenProvider.generateAccessToken(auth)).refreshToken(tokenProvider.generateRefreshToken(user.getId()))
            .tokenType("Bearer").expiresIn(props.getJwt().getAccessTokenExpiryMs()/1000).email(user.getEmail()).name(user.getName()).build();
    }
}