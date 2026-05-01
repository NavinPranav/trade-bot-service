package com.sensex.optiontrader.service;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private final SimpMessagingTemplate msg;

    public void sendAlert(Long uid, String m) {
        msg.convertAndSend("/topic/alerts/" + uid, Map.of("message", m));
    }

    /** @deprecated use {@link #sendPriceToUser(String, Map)} — global broadcast is not user-scoped */
    public void broadcastPrice(Map<String, Object> d) {
        msg.convertAndSend("/topic/live-prices", d);
    }

    /** @deprecated use {@link #sendPredictionToUser(String, Map)} */
    public void broadcastPrediction(Map<String, Object> d) {
        msg.convertAndSend("/topic/live-predictions", d);
    }

    /**
     * Sends a live tick to one STOMP user (principal name = email).
     * Client subscribes to {@code /user/topic/live-prices}.
     */
    public void sendPriceToUser(String principalName, Map<String, Object> d) {
        msg.convertAndSendToUser(principalName, "/topic/live-prices", d);
    }

    /**
     * Sends a prediction update to one STOMP user.
     * Client subscribes to {@code /user/topic/live-predictions}.
     */
    public void sendPredictionToUser(String principalName, Map<String, Object> d) {
        msg.convertAndSendToUser(principalName, "/topic/live-predictions", d);
    }

    /**
     * Sends a daily analysis notification to one STOMP user.
     * Client subscribes to {@code /user/topic/daily-analysis}.
     */
    public void sendDailyAnalysis(String principalName, Map<String, Object> payload) {
        msg.convertAndSendToUser(principalName, "/topic/daily-analysis", payload);
    }
}
