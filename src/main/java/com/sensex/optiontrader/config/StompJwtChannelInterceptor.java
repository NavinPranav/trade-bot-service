package com.sensex.optiontrader.config;

import com.sensex.optiontrader.security.CustomUserDetailsService;
import com.sensex.optiontrader.security.JwtTokenProvider;
import com.sensex.optiontrader.security.UserPrincipal;
import com.sensex.optiontrader.service.LiveMarketStreamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * STOMP CONNECT: validates JWT from {@code Authorization} native header and sets {@link StompHeaderAccessor#setUser}.
 * STOMP SUBSCRIBE to {@code /user/...} live topics: registers the session for per-user streaming.
 * Other inbound frames require an authenticated STOMP user.
 */
@Slf4j
@Component
public class StompJwtChannelInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final LiveMarketStreamService liveMarketStreamService;

    public StompJwtChannelInterceptor(JwtTokenProvider tokenProvider,
                                     CustomUserDetailsService userDetailsService,
                                     @Lazy LiveMarketStreamService liveMarketStreamService) {
        this.tokenProvider = tokenProvider;
        this.userDetailsService = userDetailsService;
        this.liveMarketStreamService = liveMarketStreamService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        if (accessor.getMessageType() == SimpMessageType.HEARTBEAT) {
            return message;
        }

        StompCommand cmd = accessor.getCommand();
        if (cmd == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(cmd)) {
            authenticateConnect(accessor);
            return message;
        }

        if (StompCommand.DISCONNECT.equals(cmd)) {
            String sid = accessor.getSessionId();
            if (sid != null) {
                liveMarketStreamService.onStompSessionClosed(sid);
            }
            return message;
        }

        if (!(accessor.getUser() instanceof UsernamePasswordAuthenticationToken auth) || !auth.isAuthenticated()) {
            throw new MessageDeliveryException("Unauthorized");
        }

        if (StompCommand.SUBSCRIBE.equals(cmd)) {
            registerIfUserLiveTopic(accessor);
        }

        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        List<String> headers = accessor.getNativeHeader("Authorization");
        if (headers == null || headers.isEmpty()) {
            throw new MessageDeliveryException("Missing Authorization header on STOMP CONNECT");
        }
        String bearer = headers.get(0);
        if (bearer == null || !bearer.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new MessageDeliveryException("Authorization must be Bearer token");
        }
        String token = bearer.substring(7).trim();
        if (token.isEmpty() || !tokenProvider.validateToken(token)) {
            throw new MessageDeliveryException("Invalid or expired token");
        }
        Long userId = tokenProvider.getUserIdFromToken(token);
        UserDetails ud = userDetailsService.loadUserById(userId);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities());
        accessor.setUser(authentication);
    }

    private void registerIfUserLiveTopic(StompHeaderAccessor accessor) {
        String dest = accessor.getDestination();
        if (dest == null || !dest.contains("/user/")) {
            return;
        }
        if (!(dest.contains("live-prices") || dest.contains("live-predictions"))) {
            return;
        }
        if (!(accessor.getUser() instanceof UsernamePasswordAuthenticationToken auth) || !auth.isAuthenticated()) {
            return;
        }
        if (!(auth.getPrincipal() instanceof UserPrincipal up)) {
            return;
        }
        String sessionId = accessor.getSessionId();
        if (sessionId == null) {
            return;
        }
        liveMarketStreamService.registerStompSession(sessionId, up.getId(), up.getUsername());
    }
}
