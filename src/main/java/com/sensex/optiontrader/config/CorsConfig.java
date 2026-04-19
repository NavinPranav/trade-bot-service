package com.sensex.optiontrader.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.*;
import org.springframework.web.filter.CorsFilter;
import java.util.List;

@Configuration
public class CorsConfig {
    @Bean public CorsFilter corsFilter() {
        var c = new CorsConfiguration();
        c.setAllowedOriginPatterns(List.of("http://localhost:*", "https://*.yourdomain.com"));
        c.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));
        c.setAllowedHeaders(List.of("*")); c.setAllowCredentials(true); c.setMaxAge(3600L);
        var s = new UrlBasedCorsConfigurationSource(); s.registerCorsConfiguration("/api/**", c); s.registerCorsConfiguration("/ws/**", c);
        return new CorsFilter(s);
    }
}