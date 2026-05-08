package com.sensex.optiontrader.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link AiPredictionFetchService#horizonSpec(String)}.
 * <p>
 * Before this fix the default branch returned ("1Y","1D"), so any intra-day
 * horizon (5M / 15M / 30M) accidentally fetched daily candles, producing
 * trend / ATR values that looked correct but were not actionable for short
 * timeframes — that is what kept HOLD locked-in across timeframes in
 * production.
 */
class AiPredictionFetchServiceHorizonSpecTest {

    private static final Set<String> INTRADAY_INTERVALS = Set.of("1M", "5M", "15M", "30M", "1H");

    @Test
    @DisplayName("intra-day horizons return intra-day candles, not daily")
    void intradayHorizonsUseIntradayCandles() {
        assertAll(
                () -> assertIntradayInterval("5M"),
                () -> assertIntradayInterval("15M"),
                () -> assertIntradayInterval("30M"),
                () -> assertIntradayInterval("1H")
        );
    }

    @Test
    @DisplayName("specific intra-day mappings match the live-stream service so both code paths see the same candles")
    void intradayMappingsAlignWithLiveStream() {
        assertEquals(new AiPredictionFetchService.HorizonSpec("3D", "1M"),
                AiPredictionFetchService.horizonSpec("5M"),
                "5M should fetch 3 days of 1-minute candles");

        assertEquals(new AiPredictionFetchService.HorizonSpec("5D", "5M"),
                AiPredictionFetchService.horizonSpec("15M"),
                "15M should fetch 5 days of 5-minute candles");

        assertEquals(new AiPredictionFetchService.HorizonSpec("7D", "5M"),
                AiPredictionFetchService.horizonSpec("30M"),
                "30M should fetch 7 days of 5-minute candles");
    }

    @Test
    @DisplayName("daily horizons keep daily candles for swing context")
    void dailyHorizonsKeepDailyCandles() {
        assertEquals("1D", AiPredictionFetchService.horizonSpec("1D").interval());
        assertEquals("1D", AiPredictionFetchService.horizonSpec("3D").interval());
        assertEquals("1D", AiPredictionFetchService.horizonSpec("1W").interval());
    }

    @Test
    @DisplayName("default branch is intra-day-safe (regression: was '1Y' / '1D')")
    void defaultIsIntradaySafe() {
        var spec = AiPredictionFetchService.horizonSpec("UNKNOWN_FUTURE_HORIZON");
        assertFalse("1D".equals(spec.interval()),
                "default fall-through must not silently use daily candles");
        assertTrue(INTRADAY_INTERVALS.contains(spec.interval()),
                "default fall-through must use a recognised intra-day interval, got " + spec.interval());
    }

    @Test
    @DisplayName("null and lowercase inputs are normalised")
    void inputNormalisation() {
        // null → safe intra-day default
        var nullSpec = AiPredictionFetchService.horizonSpec(null);
        assertTrue(INTRADAY_INTERVALS.contains(nullSpec.interval()));

        // lower-case is upper-cased
        var lower = AiPredictionFetchService.horizonSpec("15m");
        assertEquals("5M", lower.interval());
        assertEquals("5D", lower.period());
    }

    private static void assertIntradayInterval(String horizon) {
        var spec = AiPredictionFetchService.horizonSpec(horizon);
        assertTrue(INTRADAY_INTERVALS.contains(spec.interval()),
                "horizon=" + horizon + " expected intra-day interval, got " + spec.interval());
    }
}
