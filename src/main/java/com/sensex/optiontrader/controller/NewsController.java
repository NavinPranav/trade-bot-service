package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.grpc.MlRestClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NewsController {

    private final MlRestClient mlRestClient;

    @GetMapping("/sentiment")
    public ResponseEntity<?> getSentiment() {
        try {
            Map<String, Object> result = mlRestClient.getNewsSentiment();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("News sentiment unavailable: {}", e.getMessage());
            return ResponseEntity.ok(Map.of(
                    "overall", "UNAVAILABLE",
                    "score", 0,
                    "article_count", 0,
                    "bullish_count", 0,
                    "bearish_count", 0,
                    "neutral_count", 0,
                    "top_headlines", java.util.List.of(),
                    "error", e.getMessage()
            ));
        }
    }
}
