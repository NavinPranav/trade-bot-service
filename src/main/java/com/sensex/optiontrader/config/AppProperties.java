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
    /**
     * gRPC address for the ML server ({@code host} + gRPC listen port, e.g. 50051 — not HTTP 8000).
     * <p>
     * {@code transport} controls how the backend talks to the ML service:
     * <ul>
     *   <li>{@code AUTO}  — try gRPC first, fall back to REST on failure (default; good for local dev).</li>
     *   <li>{@code REST}  — REST only; never attempts gRPC. Use this on Render-style deployments where only
     *       the HTTP port is exposed and gRPC always 404s.</li>
     *   <li>{@code GRPC}  — gRPC only; no REST fallback (legacy / strict mode).</li>
     * </ul>
     * <p>
     * The minimum number of OHLCV bars to send to the ML service per horizon is enforced via
     * {@code minBarsByHorizon} so we never ship a tiny payload (5 bars) that the AI can't use.
     */
    @Data public static class MlService {
        private String host;
        private int port;
        private boolean tls;
        private long timeoutMs;
        private long backtestTimeoutMs;
        private long livePredictionIntervalMs = 60000;
        private String httpUrl = "";
        private Transport transport = Transport.AUTO;
        private java.util.Map<String, Integer> minBarsByHorizon = java.util.Map.of(
                "5M", 60,
                "15M", 120,
                "30M", 80,
                "1H", 60,
                "1D", 30,
                "3D", 30,
                "1W", 30
        );
        private int defaultMinBars = 30;
    }
    public enum Transport { AUTO, REST, GRPC }
    @Data public static class Market { private String tradingHoursStart; private String tradingHoursEnd; private String timezone; }
    @Data public static class Cache { private long predictionTtl; private long optionsChainTtl; private long marketDataTtl; }
}