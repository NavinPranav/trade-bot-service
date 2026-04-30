package com.sensex.optiontrader.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private Jwt jwt = new Jwt();
    private Admin admin = new Admin();
    private MlService mlService = new MlService();
    private Market market = new Market();
    private Cache cache = new Cache();

    /** First-time admin creation via {@code POST /api/auth/bootstrap-admin}; leave secret empty to disable. */
    @Data public static class Admin { private String bootstrapSecret; }

    @Data public static class Jwt { private String secret; private long accessTokenExpiryMs; private long refreshTokenExpiryMs; }
    /** gRPC address for the ML server ({@code host} + gRPC listen port, e.g. 50051 — not HTTP 8000). */
    @Data public static class MlService { private String host; private int port; private boolean tls; private long timeoutMs; private long backtestTimeoutMs; private long livePredictionIntervalMs = 60000; private String httpUrl = ""; }
    @Data public static class Market { private String tradingHoursStart; private String tradingHoursEnd; private String timezone; }
    @Data public static class Cache { private long predictionTtl; private long optionsChainTtl; private long marketDataTtl; }
}