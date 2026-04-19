package com.sensex.optiontrader.integration.angelone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sensex.optiontrader.config.AngelOneProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages Angel One SmartAPI authentication lifecycle: TOTP-based login, JWT token refresh,
 * and feed-token acquisition for WebSocket streaming.
 */
@Slf4j
@Service
public class AngelOneAuthService {

    private final AngelOneProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    private final AtomicReference<String> jwtToken = new AtomicReference<>();
    private final AtomicReference<String> refreshToken = new AtomicReference<>();
    @Getter
    private final AtomicReference<String> feedToken = new AtomicReference<>();

    public AngelOneAuthService(AngelOneProperties props, RestClient angelOneRestClient, ObjectMapper objectMapper) {
        this.props = props;
        this.restClient = angelOneRestClient;
        this.objectMapper = objectMapper;
    }

    public String getJwtToken() {
        return jwtToken.get();
    }

    public boolean isAuthenticated() {
        return jwtToken.get() != null && feedToken.get() != null;
    }

    /**
     * Full login using client code, password, and a freshly generated TOTP.
     * Must be called before any API or WebSocket usage.
     */
    public void login() {
        String totp = TotpUtil.generateTotp(props.totpSecret());
        log.info("Angel One login: client={}", props.clientCode());

        Map<String, String> body = Map.of(
                "clientcode", props.clientCode(),
                "password", props.password(),
                "totp", totp
        );

        try {
            String raw = restClient.post()
                    .uri("/rest/auth/angelbroking/user/v1/loginByPassword")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(raw);
            if (!root.path("status").asBoolean(false)) {
                log.error("Angel One login failed: {}", root.path("message").asText("unknown error"));
                throw new IllegalStateException("Angel One login failed: " + root.path("message").asText());
            }

            JsonNode data = root.path("data");
            jwtToken.set(data.path("jwtToken").asText(null));
            refreshToken.set(data.path("refreshToken").asText(null));
            feedToken.set(data.path("feedToken").asText(null));

            log.info("Angel One login successful — JWT and feed token acquired");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Angel One login request failed: {}", e.getMessage());
            throw new IllegalStateException("Angel One login failed", e);
        }
    }

    /**
     * Refreshes the JWT using the refresh token. Falls back to full re-login on failure.
     */
    @Scheduled(fixedDelayString = "${app.market.angel-one.token-refresh-interval-ms:300000}",
               initialDelayString = "${app.market.angel-one.token-refresh-interval-ms:300000}")
    public void refreshSession() {
        String rt = refreshToken.get();
        if (rt == null) {
            log.debug("No refresh token available — skipping refresh");
            return;
        }

        try {
            String raw = restClient.post()
                    .uri("/rest/auth/angelbroking/jwt/v1/generateTokens")
                    .header("Authorization", "Bearer " + jwtToken.get())
                    .body(Map.of("refreshToken", rt))
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(raw);
            if (root.path("status").asBoolean(false)) {
                JsonNode data = root.path("data");
                jwtToken.set(data.path("jwtToken").asText(jwtToken.get()));
                refreshToken.set(data.path("refreshToken").asText(rt));
                feedToken.set(data.path("feedToken").asText(feedToken.get()));
                log.debug("Angel One token refreshed");
            } else {
                log.warn("Angel One token refresh failed ({}), attempting full re-login", root.path("message").asText());
                login();
            }
        } catch (Exception e) {
            log.warn("Angel One token refresh error: {} — attempting full re-login", e.getMessage());
            try {
                login();
            } catch (Exception loginEx) {
                log.error("Angel One re-login also failed: {}", loginEx.getMessage());
            }
        }
    }

    public void logout() {
        String jwt = jwtToken.get();
        if (jwt == null) return;
        try {
            restClient.post()
                    .uri("/rest/secure/angelbroking/user/v1/logout")
                    .header("Authorization", "Bearer " + jwt)
                    .body(Map.of("clientcode", props.clientCode()))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Angel One logout successful");
        } catch (Exception e) {
            log.warn("Angel One logout failed (non-critical): {}", e.getMessage());
        } finally {
            jwtToken.set(null);
            refreshToken.set(null);
            feedToken.set(null);
        }
    }
}
