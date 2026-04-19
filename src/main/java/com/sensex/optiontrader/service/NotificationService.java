package com.sensex.optiontrader.service;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;
@Service @RequiredArgsConstructor public class NotificationService {
    private final SimpMessagingTemplate msg;
    public void sendAlert(Long uid, String m) { msg.convertAndSend("/topic/alerts/"+uid, Map.of("message",m)); }
    public void broadcastPrice(Map<String,Object> d) { msg.convertAndSend("/topic/live-prices", d); }
    public void broadcastPrediction(Map<String,Object> d) { msg.convertAndSend("/topic/live-predictions", d); }
}