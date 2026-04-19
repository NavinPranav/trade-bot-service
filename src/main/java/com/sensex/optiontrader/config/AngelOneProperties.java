package com.sensex.optiontrader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "app.market.angel-one")
public record AngelOneProperties(
        String apiKey,
        String clientCode,
        String password,
        String totpSecret,
        @DefaultValue("https://apiconnect.angelone.in") String baseUrl,
        @DefaultValue("wss://smartapisocket.angelone.in/smart-stream") String wsUrl,
        @DefaultValue("2") int subscriptionMode,
        @DefaultValue("5000") long reconnectDelayMs,
        @DefaultValue("300000") long tokenRefreshIntervalMs,
        List<InstrumentToken> instruments) {

    public record InstrumentToken(
            String name,
            String exchange,
            String token,
            @DefaultValue("1") int exchangeType) {
    }
}
