package com.sensex.optiontrader.integration.angelone;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable tick event parsed from an Angel One SmartAPI WebSocket binary frame (Quote mode).
 */
@Value
@Builder
public class LiveTickData {
    int subscriptionMode;
    int exchangeType;
    String token;
    long sequenceNumber;
    long exchangeTimestampMs;
    double lastTradedPrice;
    int lastTradedQuantity;
    double averageTradedPrice;
    long volumeTraded;
    double totalBuyQuantity;
    double totalSellQuantity;
    double openPrice;
    double highPrice;
    double lowPrice;
    double closePrice;

    /** Convenience: LTP − previous close. */
    public double change() {
        return closePrice > 0 ? lastTradedPrice - closePrice : 0;
    }

    /** Convenience: percentage change from previous close. */
    public double changePct() {
        return closePrice > 0 ? (lastTradedPrice - closePrice) / closePrice * 100.0 : 0;
    }
}
