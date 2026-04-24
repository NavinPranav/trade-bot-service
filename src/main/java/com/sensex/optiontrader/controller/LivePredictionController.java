package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.security.UserPrincipal;
import com.sensex.optiontrader.service.LiveMarketStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class LivePredictionController {

    private final LiveMarketStreamService liveStream;

    @MessageMapping("/predict/subscribe")
    public void subscribe(Principal principal, @Payload Map<String, String> msg) {
        UserPrincipal up = requireUser(principal);
        String horizon = msg.getOrDefault("horizon", "1D");
        liveStream.subscribeLivePredictions(up.getId(), up.getUsername(), horizon);
    }

    @MessageMapping("/predict/unsubscribe")
    public void unsubscribe(Principal principal) {
        UserPrincipal up = requireUser(principal);
        liveStream.unsubscribeLivePredictions(up.getId());
    }

    @MessageMapping("/predict")
    public void predict(Principal principal, @Payload Map<String, String> msg) {
        UserPrincipal up = requireUser(principal);
        String horizon = msg.getOrDefault("horizon", "1D");
        liveStream.requestPrediction(horizon, up.getId(), up.getUsername());
    }

    private static UserPrincipal requireUser(Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken auth
                && auth.getPrincipal() instanceof UserPrincipal up) {
            return up;
        }
        if (principal instanceof UserPrincipal up) {
            return up;
        }
        throw new IllegalStateException("Unauthenticated STOMP message");
    }
}
