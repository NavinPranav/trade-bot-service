package com.sensex.optiontrader.model.enums;

public enum Direction {
    BULLISH, BEARISH, NEUTRAL,
    BUY, HOLD, SELL;

    /**
     * Normalises Gemini signals to market-bias equivalents for strategy selection:
     * BUY → BULLISH, SELL → BEARISH, HOLD → NEUTRAL.
     * ML values pass through unchanged.
     */
    public Direction toBias() {
        return switch (this) {
            case BUY -> BULLISH;
            case SELL -> BEARISH;
            case HOLD -> NEUTRAL;
            default -> this;
        };
    }
}