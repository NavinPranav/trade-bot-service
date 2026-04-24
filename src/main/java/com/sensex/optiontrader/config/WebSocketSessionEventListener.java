package com.sensex.optiontrader.config;

import com.sensex.optiontrader.service.LiveMarketStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Ensures cleanup when the WebSocket transport drops without a STOMP DISCONNECT frame.
 */
@Component
@RequiredArgsConstructor
public class WebSocketSessionEventListener {

    private final LiveMarketStreamService liveMarketStreamService;

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        liveMarketStreamService.onStompSessionClosed(event.getSessionId());
    }
}
