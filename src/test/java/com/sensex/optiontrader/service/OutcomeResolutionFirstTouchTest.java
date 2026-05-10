package com.sensex.optiontrader.service;

import com.sensex.optiontrader.model.enums.Direction;
import com.sensex.optiontrader.model.enums.OutcomeStatus;
import com.sensex.optiontrader.service.OutcomeResolutionService.FirstTouchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-function regression tests for the first-touch resolver inside
 * {@link OutcomeResolutionService}. Exercises the static
 * {@code classifyFromBars} helper without touching Spring or Angel One —
 * the same logic runs in production but is fed live OHLC bars.
 */
class OutcomeResolutionFirstTouchTest {

    private static Map<String, Object> bar(double open, double high, double low, double close) {
        return Map.of(
                "timestamp", "2026-05-08T09:30:00",
                "open", BigDecimal.valueOf(open),
                "high", BigDecimal.valueOf(high),
                "low", BigDecimal.valueOf(low),
                "close", BigDecimal.valueOf(close));
    }

    // ── Interval picker ────────────────────────────────────────────────────

    @Test
    @DisplayName("pickInterval: short windows use 1-minute candles, long windows use coarser bars")
    void pickIntervalScalesWithWindowSize() {
        assertEquals("1M", OutcomeResolutionService.pickInterval(15));
        assertEquals("1M", OutcomeResolutionService.pickInterval(30));
        assertEquals("5M", OutcomeResolutionService.pickInterval(60));
        assertEquals("5M", OutcomeResolutionService.pickInterval(240));
        assertEquals("15M", OutcomeResolutionService.pickInterval(480));
    }

    // ── BUY / long bias ────────────────────────────────────────────────────

    @Test
    @DisplayName("BUY: target touched in 2nd bar before close reverts → TARGET_HIT")
    void buyTargetHitDespiteRevertedClose() {
        // Entry 100, target 105, stop 95.
        // Bar 1: trades 100..102 (no trigger). Bar 2: spikes to 105.5 then closes back at 99.
        // Close-only would (wrongly) call this EXPIRED with -1% P&L. First-touch must call it TARGET_HIT.
        var bars = List.of(
                bar(100.0, 102.0, 99.5, 101.5),
                bar(101.5, 105.5, 99.0, 99.0));

        FirstTouchResult ft = OutcomeResolutionService.classifyFromBars(
                Direction.BUY, BigDecimal.valueOf(105.0), BigDecimal.valueOf(95.0), bars, true);

        assertTrue(ft.isResolved());
        assertEquals(OutcomeStatus.TARGET_HIT, ft.status());
        assertEquals(0, ft.exitPrice().compareTo(BigDecimal.valueOf(105.0)));
        // Window high/low captured across all walked bars.
        assertEquals(0, ft.windowHigh().compareTo(BigDecimal.valueOf(105.5)));
        assertEquals(0, ft.windowLow().compareTo(BigDecimal.valueOf(99.0)));
    }

    @Test
    @DisplayName("BUY: stop touched first in chronological order → STOP_LOSS_HIT")
    void buyStopHitFirstInTime() {
        // Bar 1 takes out the stop at 94. Bar 2 then spikes to 110.
        // First-touch must call STOP_LOSS_HIT — the trade is already closed.
        var bars = List.of(
                bar(100.0, 102.0, 94.0, 96.0),
                bar(96.0, 110.0, 95.0, 108.0));

        FirstTouchResult ft = OutcomeResolutionService.classifyFromBars(
                Direction.BUY, BigDecimal.valueOf(105.0), BigDecimal.valueOf(95.0), bars, true);

        assertTrue(ft.isResolved());
        assertEquals(OutcomeStatus.STOP_LOSS_HIT, ft.status());
        assertEquals(0, ft.exitPrice().compareTo(BigDecimal.valueOf(95.0)));
    }

    @Test
    @DisplayName("BUY: same bar straddles both → STOP_LOSS_HIT under conservative tie-breaker")
    void buySameBarStopWinsByDefault() {
        var bars = List.of(bar(100.0, 106.0, 94.0, 100.0));

        FirstTouchResult ft = OutcomeResolutionService.classifyFromBars(
                Direction.BUY, BigDecimal.valueOf(105.0), BigDecimal.valueOf(95.0), bars, true);

        assertEquals(OutcomeStatus.STOP_LOSS_HIT, ft.status());
        assertEquals(0, ft.exitPrice().compareTo(BigDecimal.valueOf(95.0)));
    }

    @Test
    @DisplayName("BUY: same bar with stopWinsOnSameBar=false → TARGET_HIT (optimistic)")
    void buySameBarTargetWinsWhenConfigured() {
        var bars = List.of(bar(100.0, 106.0, 94.0, 100.0));

        FirstTouchResult ft = OutcomeResolutionService.classifyFromBars(
                Direction.BUY, BigDecimal.valueOf(105.0), BigDecimal.valueOf(95.0), bars, false);

        assertEquals(OutcomeStatus.TARGET_HIT, ft.status());
        assertEquals(0, ft.exitPrice().compareTo(BigDecimal.valueOf(105.0)));
    }

    @Test
    @DisplayName("BUY: neither level touched → unresolved with accurate window high/low")
    void buyNeitherTouchedIsUnresolved() {
        var bars = List.of(
                bar(100.0, 103.5, 97.0, 102.0),
                bar(102.0, 104.0, 96.5, 100.5));

        FirstTouchResult ft = OutcomeResolutionService.classifyFromBars(
                Direction.BUY, BigDecimal.valueOf(105.0), BigDecimal.valueOf(95.0), bars, true);

        assertFalse(ft.isResolved());
        assertNull(ft.status());
        assertNull(ft.exitPrice());
        // Window high/low aggregated across all bars walked.
        assertEquals(0, ft.windowHigh().compareTo(BigDecimal.valueOf(104.0)));
        assertEquals(0, ft.windowLow().compareTo(BigDecimal.valueOf(96.5)));
    }

    // ── SELL / short bias ──────────────────────────────────────────────────

    @Test
    @DisplayName("SELL: target reached on the way down (low ≤ target) → TARGET_HIT")
    void sellTargetHit() {
        // Entry 100, target 95 (down), stop 105 (up).
        var bars = List.of(
                bar(100.0, 100.5, 99.0, 99.5),
                bar(99.5, 99.8, 94.5, 96.0));

        FirstTouchResult ft = OutcomeResolutionService.classifyFromBars(
                Direction.SELL, BigDecimal.valueOf(95.0), BigDecimal.valueOf(105.0), bars, true);

        assertEquals(OutcomeStatus.TARGET_HIT, ft.status());
        assertEquals(0, ft.exitPrice().compareTo(BigDecimal.valueOf(95.0)));
    }

    @Test
    @DisplayName("SELL: stop reached on the way up (high ≥ stop) → STOP_LOSS_HIT")
    void sellStopHit() {
        var bars = List.of(bar(100.0, 105.5, 99.0, 105.0));

        FirstTouchResult ft = OutcomeResolutionService.classifyFromBars(
                Direction.SELL, BigDecimal.valueOf(95.0), BigDecimal.valueOf(105.0), bars, true);

        assertEquals(OutcomeStatus.STOP_LOSS_HIT, ft.status());
        assertEquals(0, ft.exitPrice().compareTo(BigDecimal.valueOf(105.0)));
    }

    @Test
    @DisplayName("BULLISH alias is treated like BUY")
    void bullishAliasMatchesBuy() {
        var bars = List.of(bar(100.0, 105.0, 99.0, 100.0));

        FirstTouchResult ft = OutcomeResolutionService.classifyFromBars(
                Direction.BULLISH, BigDecimal.valueOf(105.0), BigDecimal.valueOf(95.0), bars, true);

        assertEquals(OutcomeStatus.TARGET_HIT, ft.status());
    }

    @Test
    @DisplayName("BEARISH alias is treated like SELL")
    void bearishAliasMatchesSell() {
        var bars = List.of(bar(100.0, 100.0, 95.0, 95.5));

        FirstTouchResult ft = OutcomeResolutionService.classifyFromBars(
                Direction.BEARISH, BigDecimal.valueOf(95.0), BigDecimal.valueOf(105.0), bars, true);

        assertEquals(OutcomeStatus.TARGET_HIT, ft.status());
    }

    // ── Defensive cases ────────────────────────────────────────────────────

    @Test
    @DisplayName("HOLD direction returns no trigger but still aggregates window high/low")
    void holdDirectionIsNeverResolved() {
        var bars = List.of(bar(100.0, 110.0, 90.0, 100.0));

        FirstTouchResult ft = OutcomeResolutionService.classifyFromBars(
                Direction.HOLD, BigDecimal.valueOf(105.0), BigDecimal.valueOf(95.0), bars, true);

        assertFalse(ft.isResolved());
        assertEquals(0, ft.windowHigh().compareTo(BigDecimal.valueOf(110.0)));
        assertEquals(0, ft.windowLow().compareTo(BigDecimal.valueOf(90.0)));
    }

    @Test
    @DisplayName("Bar with missing high/low fields is skipped without throwing")
    void malformedBarIsSkipped() {
        var bars = List.of(
                Map.<String, Object>of("open", 100.0, "close", 100.0),  // missing high/low
                bar(100.0, 105.0, 95.0, 100.0));

        FirstTouchResult ft = OutcomeResolutionService.classifyFromBars(
                Direction.BUY, BigDecimal.valueOf(105.0), BigDecimal.valueOf(95.0), bars, true);

        // Same-bar straddle on the second bar → conservative stop wins.
        assertEquals(OutcomeStatus.STOP_LOSS_HIT, ft.status());
    }

    @Test
    @DisplayName("Empty bar list returns unresolved with no window stats")
    void emptyBarListUnresolved() {
        FirstTouchResult ft = OutcomeResolutionService.classifyFromBars(
                Direction.BUY, BigDecimal.valueOf(105.0), BigDecimal.valueOf(95.0), List.of(), true);

        assertFalse(ft.isResolved());
        assertNull(ft.windowHigh());
        assertNull(ft.windowLow());
    }

    @Test
    @DisplayName("BUY with no stop set still triggers TARGET_HIT when target is touched")
    void missingStopDoesNotPreventTargetHit() {
        var bars = List.of(bar(100.0, 106.0, 99.0, 102.0));

        FirstTouchResult ft = OutcomeResolutionService.classifyFromBars(
                Direction.BUY, BigDecimal.valueOf(105.0), null, bars, true);

        assertEquals(OutcomeStatus.TARGET_HIT, ft.status());
    }
}
