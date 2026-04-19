package com.sensex.optiontrader.security;

import com.sensex.optiontrader.config.AppProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.util.Date;

@Slf4j @Component @RequiredArgsConstructor
public class JwtTokenProvider {
    private final AppProperties props;
    private SecretKey key;

    @PostConstruct public void init() { key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(props.getJwt().getSecret())); }

    public String generateAccessToken(Authentication auth) {
        var p = (UserPrincipal) auth.getPrincipal(); var now = new Date();
        return Jwts.builder().subject(p.getId().toString()).claim("email", p.getEmail())
            .issuedAt(now).expiration(new Date(now.getTime() + props.getJwt().getAccessTokenExpiryMs())).signWith(key).compact();
    }
    public String generateRefreshToken(Long userId) {
        var now = new Date();
        return Jwts.builder().subject(userId.toString()).issuedAt(now)
            .expiration(new Date(now.getTime() + props.getJwt().getRefreshTokenExpiryMs())).signWith(key).compact();
    }
    public Long getUserIdFromToken(String token) {
        return Long.parseLong(Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject());
    }
    public boolean validateToken(String token) {
        try { Jwts.parser().verifyWith(key).build().parseSignedClaims(token); return true; }
        catch (JwtException | IllegalArgumentException e) { log.warn("Invalid JWT: {}", e.getMessage()); return false; }
    }
}