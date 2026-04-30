package com.sensex.optiontrader.service;

import com.sensex.optiontrader.config.AppProperties;
import com.sensex.optiontrader.exception.*;
import com.sensex.optiontrader.model.enums.UserRole;
import com.sensex.optiontrader.model.dto.request.*;
import com.sensex.optiontrader.model.dto.response.AuthResponse;
import com.sensex.optiontrader.model.dto.response.CurrentUserResponse;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

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

    /**
     * One-time setup: creates the first {@link UserRole#ADMIN} or promotes an existing user when no admin exists yet.
     * Requires {@code app.admin.bootstrap-secret} and matching {@code X-Admin-Bootstrap-Secret} header.
     */
    @Transactional
    public AuthResponse bootstrapAdmin(RegisterRequest req, String providedSecret) {
        String configured = props.getAdmin().getBootstrapSecret();
        if (configured == null || configured.isBlank())
            throw new ForbiddenException("Admin bootstrap is disabled (set ADMIN_BOOTSTRAP_SECRET or app.admin.bootstrap-secret)");
        if (!secureEquals(configured, providedSecret != null ? providedSecret : ""))
            throw new UnauthorizedException("Invalid bootstrap secret");
        if (userRepo.countByRole(UserRole.ADMIN) > 0)
            throw new ForbiddenException("An admin user already exists; bootstrap is one-time only");

        var existingOpt = userRepo.findByEmail(req.getEmail());
        if (existingOpt.isPresent()) {
            var user = existingOpt.get();
            if (user.getRole() == UserRole.ADMIN)
                throw new BadRequestException("This account is already an admin");
            if (!passwordEncoder.matches(req.getPassword(), user.getPassword()))
                throw new BadRequestException("Invalid password for this email");
            user.setRole(UserRole.ADMIN);
            userRepo.save(user);
            var auth = authManager.authenticate(new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword()));
            return buildAuth(auth, user);
        }
        var bankNifty = instrumentRepo.findByNameIgnoreCase("BANKNIFTY")
                .orElseThrow(() -> new BadRequestException("Default instrument BANKNIFTY is not configured"));
        var user = User.builder()
                .name(req.getName())
                .email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))
                .role(UserRole.ADMIN)
                .preferredInstrument(bankNifty)
                .build();
        userRepo.save(user);
        var auth = authManager.authenticate(new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword()));
        return buildAuth(auth, user);
    }

    private static boolean secureEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public CurrentUserResponse getCurrentUser(Long userId) {
        var u = userRepo.findById(userId).orElseThrow(() -> new UnauthorizedException("User not found"));
        return CurrentUserResponse.builder()
                .email(u.getEmail())
                .name(u.getName())
                .role(u.getRole() != null ? u.getRole().name() : UserRole.USER.name())
                .build();
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
            .tokenType("Bearer").expiresIn(props.getJwt().getAccessTokenExpiryMs()/1000).email(user.getEmail()).name(user.getName())
            .role(user.getRole() != null ? user.getRole().name() : "USER").build();
    }
}