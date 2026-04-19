package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.service.LiveMarketStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
@RequiredArgsConstructor
public class LivePredictionController {

    private final LiveMarketStreamService liveStream;

    /**
     * STOMP endpoint: the UI sends {@code {"horizon":"1W"}} to {@code /app/predict/subscribe}.
     * This sets the active horizon and starts a periodic live-prediction loop.
     * Re-sending with a different horizon switches the loop immediately.
     */
    @MessageMapping("/predict/subscribe")
    public void subscribe(@Payload Map<String, String> msg) {
        String horizon = msg.getOrDefault("horizon", "1D");
        liveStream.subscribeLivePredictions(horizon);
    }

    /**
     * STOMP endpoint: the UI sends any message to {@code /app/predict/unsubscribe}
     * to stop the live-prediction loop (e.g. when navigating away from the dashboard).
     */
    @MessageMapping("/predict/unsubscribe")
    public void unsubscribe() {
        liveStream.unsubscribeLivePredictions();
    }

    /**
     * Backward-compatible one-shot prediction. The UI sends {@code {"horizon":"1D"}}
     * to {@code /app/predict} for a single prediction without starting the live loop.
     */
    @MessageMapping("/predict")
    public void predict(@Payload Map<String, String> msg) {
        String horizon = msg.getOrDefault("horizon", "1D");
        liveStream.requestPrediction(horizon);
    }
}
