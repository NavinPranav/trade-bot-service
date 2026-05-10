package com.sensex.optiontrader.service;

import com.sensex.optiontrader.config.AngelOneProperties.InstrumentToken;
import com.sensex.optiontrader.config.AppProperties;
import com.sensex.optiontrader.integration.MarketDataProvider;
import com.sensex.optiontrader.integration.angelone.AngelOneHistoricalClient;
import com.sensex.optiontrader.integration.angelone.LiveTickData;
import com.sensex.optiontrader.model.entity.Prediction;
import com.sensex.optiontrader.model.enums.Direction;
import com.sensex.optiontrader.model.enums.OutcomeStatus;
import com.sensex.optiontrader.repository.PredictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves predictions once their validity window closes.
 *
 * <h2>v1 — close-only (legacy, still used as fallback)</h2>
 * Compares the price at window-end against {@code targetSensex} / {@code stopLoss}.
 * Misses cases where price touches one of the levels intraday but reverts.
 *
 * <h2>v2 — first-touch (Phase 2, default ON)</h2>
 * When {@code app.outcome.first-touch-enabled=true} we fetch the OHLC bars that
 * span {@code [predictionTimestamp, predictionTimestamp + validMinutes]} from
 * Angel One historical and walk them in chronological order. Whichever of
 * {@code targetSensex} / {@code stopLoss} is touched first wins, with
 * {@code stopWinsOnSameBar} as a conservative tie-breaker when a single bar's
 * range straddles both levels (we have no intra-bar ordering, so we assume the
 * stop fires first — the standard back-testing convention).
 *
 * <p>This produces dramatically more accurate {@code outcomeStatus} and
 * {@code actualPnlPct} values, which in turn power the daily-loss risk limit
 * and the new confidence-calibration loop (Phase 4.3). Both depend on having
 * faithful labels — close-only resolution systematically over-states "target
 * hit" when price reverts back into the entry range by window end.
 */
@Slf4j
@Service
public class OutcomeResolutionService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Matches ML coerce default when DB row has null valid_minutes. */
    private static final int DEFAULT_VALID_MINUTES = 15;

    private final PredictionRepository predictionRepo;
    private final MarketDataProvider marketDataProvider;
    private final InstrumentRegistry instrumentRegistry;
    private final AppProperties props;

    /**
     * Optional — historical client may be null in unit tests where we exercise
     * the close-only fallback path without an Angel One round-trip.
     */
    private final AngelOneHistoricalClient historicalClient;

    @Autowired
    public OutcomeResolutionService(PredictionRepository predictionRepo,
                                    MarketDataProvider marketDataProvider,
                                    InstrumentRegistry instrumentRegistry,
                                    AppProperties props,
                                    AngelOneHistoricalClient historicalClient) {
        this.predictionRepo = predictionRepo;
        this.marketDataProvider = marketDataProvider;
        this.instrumentRegistry = instrumentRegistry;
        this.props = props;
        this.historicalClient = historicalClient;
    }

    /**
     * Every minute: resolve predictions whose validity window has ended ({@code predictionTimestamp + validMinutes}).
     * Uses latest streamed LTP when available, then prediction-time {@code currentSensex} as fallback.
     * Sets {@code actualClosePrice}, outcome (target hit / SL hit / expired), and P&amp;L % where applicable.
     */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Kolkata")
    @Transactional
    public void resolveDuePredictions() {
        Instant now = Instant.now();
        List<Prediction> pending = predictionRepo.findPendingForOutcomeResolution();
        List<Prediction> updated = new ArrayList<>();

        for (Prediction p : pending) {
            int validMin = p.getValidMinutes() != null && p.getValidMinutes() > 0
                    ? p.getValidMinutes()
                    : DEFAULT_VALID_MINUTES;
            Instant windowEnd = p.getPredictionTimestamp().plus(validMin, ChronoUnit.MINUTES);
            if (windowEnd.isAfter(now)) {
                continue;
            }
            if (tryResolveWithMarketPrice(p, now)) {
                updated.add(p);
            }
        }

        if (!updated.isEmpty()) {
            predictionRepo.saveAll(updated);
            log.info("[OUTCOME] Resolved {} prediction(s) after validity window", updated.size());
        }
    }

    /**
     * Weekdays 15:35 IST: sweep same-day rows still {@link OutcomeStatus#PENDING} (no tick yet, etc.).
     * Applies a last attempt to capture a price; otherwise marks {@link OutcomeStatus#EXPIRED} so nothing stays pending forever.
     */
    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    @Transactional
    public void resolveStaleAtMarketClose() {
        LocalDate today = LocalDate.now(IST);
        List<Prediction> pending = predictionRepo.findPendingByDate(today);
        if (pending.isEmpty()) {
            log.debug("[OUTCOME] EOD sweep: no pending predictions for {}", today);
            return;
        }

        Instant now = Instant.now();
        List<Prediction> changed = new ArrayList<>();
        for (Prediction p : pending) {
            if (p.getOutcomeStatus() != OutcomeStatus.PENDING) {
                continue;
            }
            if (tryResolveWithMarketPrice(p, now)) {
                changed.add(p);
            } else {
                p.setOutcomeStatus(OutcomeStatus.EXPIRED);
                p.setOutcomeEvaluatedAt(now);
                changed.add(p);
                log.debug("[OUTCOME] EOD EXPIRED id={} (no price)", p.getId());
            }
        }
        predictionRepo.saveAll(changed);
        log.info("[OUTCOME] EOD sweep: {} prediction(s) closed for {}", changed.size(), today);
    }

    /**
     * @return true if the row was fully resolved (has a price and final outcome).
     */
    private boolean tryResolveWithMarketPrice(Prediction p, Instant evaluatedAt) {
        Optional<Double> priceOpt = resolveLtp(p);
        if (priceOpt.isEmpty()) {
            log.debug("[OUTCOME] No price yet for prediction id={}", p.getId());
            return false;
        }

        double ltp = priceOpt.get();
        BigDecimal closeBd = BigDecimal.valueOf(ltp).setScale(2, RoundingMode.HALF_UP);
        p.setActualClosePrice(closeBd);

        Direction dir = p.getDirection();

        // ── Phase 2: first-touch resolution ─────────────────────────────────
        // Walk the OHLC bars inside the validity window. If the price touched
        // target or stop before the window ended we use that — even if the
        // close at window-end has reverted past the level.
        Optional<FirstTouchResult> firstTouch = tryFirstTouchResolve(p);
        if (firstTouch.isPresent()) {
            FirstTouchResult ft = firstTouch.get();
            // Always store the WINDOW high/low (more accurate than day high/low
            // returned by the live tick).
            if (ft.windowHigh() != null) {
                p.setActualHighPrice(ft.windowHigh().setScale(2, RoundingMode.HALF_UP));
            }
            if (ft.windowLow() != null) {
                p.setActualLowPrice(ft.windowLow().setScale(2, RoundingMode.HALF_UP));
            }
            if (ft.isResolved()) {
                applyResolvedOutcome(p, ft, dir, evaluatedAt);
                log.info(
                        "[OUTCOME] First-touch resolved id={} dir={} status={} exit={} (high={} low={})",
                        p.getId(), dir, ft.status(), ft.exitPrice(), ft.windowHigh(), ft.windowLow());
                return true;
            }
            // Bars walked, no trigger → close-only fallback below, but window
            // high/low have already been set accurately above.
        } else {
            // First-touch unavailable (disabled, no token, fetch failed, etc.)
            // → fall back to the legacy day-high/low from the live tick so the
            // UI still has *something* to show.
            populateDayHighLowFromTick(p);
        }

        OutcomeStatus status = classifyOutcome(p, closeBd, dir);
        p.setOutcomeStatus(status);
        p.setOutcomeEvaluatedAt(evaluatedAt);
        computePnl(p, closeBd, dir);
        return true;
    }

    private void applyResolvedOutcome(Prediction p, FirstTouchResult ft, Direction dir, Instant evaluatedAt) {
        p.setOutcomeStatus(ft.status());
        p.setTargetHit(ft.status() == OutcomeStatus.TARGET_HIT);
        p.setStopLossHit(ft.status() == OutcomeStatus.STOP_LOSS_HIT);
        p.setOutcomeEvaluatedAt(evaluatedAt);
        // P&L is computed from the level we touched, not the close at window-end.
        // This is the whole point of first-touch: the trade would have closed at
        // the trigger price, not whatever the price reverted to.
        computePnl(p, ft.exitPrice(), dir);
    }

    private void populateDayHighLowFromTick(Prediction p) {
        Optional<String> tokenOpt = resolveToken(p);
        if (tokenOpt.isEmpty()) return;
        LiveTickData tick = marketDataProvider.getLatestTick(tokenOpt.get());
        if (tick == null) return;
        if (tick.getHighPrice() > 0) {
            p.setActualHighPrice(BigDecimal.valueOf(tick.getHighPrice()).setScale(2, RoundingMode.HALF_UP));
        }
        if (tick.getLowPrice() > 0) {
            p.setActualLowPrice(BigDecimal.valueOf(tick.getLowPrice()).setScale(2, RoundingMode.HALF_UP));
        }
    }

    /**
     * Attempts to resolve the prediction by walking the historical OHLC bars
     * inside its validity window. Returns {@code Optional.empty()} when the
     * data path is unavailable (config off, no instrument, no bars, etc.); the
     * caller falls back to the close-only path. When bars are available but
     * neither target nor stop was touched, the returned result has
     * {@code status() == null} — caller still uses the captured window high/low.
     */
    private Optional<FirstTouchResult> tryFirstTouchResolve(Prediction p) {
        if (!props.getOutcome().isFirstTouchEnabled()) return Optional.empty();
        if (historicalClient == null) return Optional.empty();
        if (p.getPredictionTimestamp() == null || p.getValidMinutes() == null) return Optional.empty();
        if (p.getDirection() == null) return Optional.empty();

        Optional<InstrumentToken> instOpt = instrumentRegistry
                .getPrimaryForUser(p.getUser().getId());
        if (instOpt.isEmpty()) return Optional.empty();

        int validMin = p.getValidMinutes() > 0 ? p.getValidMinutes() : DEFAULT_VALID_MINUTES;
        LocalDateTime startLdt = LocalDateTime.ofInstant(p.getPredictionTimestamp(), IST);
        LocalDateTime endLdt = LocalDateTime.ofInstant(
                p.getPredictionTimestamp().plus(validMin, ChronoUnit.MINUTES), IST);

        String interval = pickInterval(validMin);
        List<Map<String, Object>> bars;
        try {
            bars = historicalClient.getOhlcv(instOpt.get(), startLdt, endLdt, interval);
        } catch (Exception e) {
            log.warn("[OUTCOME] First-touch fetch failed id={} err={}", p.getId(), e.getMessage());
            return Optional.empty();
        }
        if (bars == null || bars.isEmpty()) {
            log.debug(
                    "[OUTCOME] First-touch: no bars for id={} window={}..{} interval={}",
                    p.getId(), startLdt, endLdt, interval);
            return Optional.empty();
        }

        return Optional.of(classifyFromBars(
                p.getDirection(),
                p.getTargetSensex(),
                p.getStopLoss(),
                bars,
                props.getOutcome().isStopWinsOnSameBar()));
    }

    private Optional<String> resolveToken(Prediction p) {
        String stored = p.getInstrumentToken();
        if (stored != null && !stored.isBlank()) {
            return Optional.of(stored.trim());
        }
        return instrumentRegistry.getPrimaryForUser(p.getUser().getId()).map(InstrumentToken::token);
    }

    private Optional<Double> resolveLtp(Prediction p) {
        Optional<String> tokenOpt = resolveToken(p);
        if (tokenOpt.isPresent()) {
            LiveTickData tick = marketDataProvider.getLatestTick(tokenOpt.get());
            if (tick != null && tick.getLastTradedPrice() > 0) {
                return Optional.of(tick.getLastTradedPrice());
            }
        }
        if (p.getCurrentSensex() != null && p.getCurrentSensex().compareTo(BigDecimal.ZERO) > 0) {
            return Optional.of(p.getCurrentSensex().doubleValue());
        }
        return Optional.empty();
    }

    /**
     * Classify using <strong>closing price vs levels</strong> (legacy v1; does not simulate intraday path order).
     */
    private static OutcomeStatus classifyOutcome(Prediction p, BigDecimal close, Direction dir) {
        boolean longBias = dir == Direction.BUY || dir == Direction.BULLISH;
        boolean shortBias = dir == Direction.SELL || dir == Direction.BEARISH;

        if (!longBias && !shortBias) {
            p.setTargetHit(false);
            p.setStopLossHit(false);
            return OutcomeStatus.EXPIRED;
        }

        BigDecimal target = p.getTargetSensex();
        BigDecimal stop = p.getStopLoss();

        if (longBias) {
            if (target != null && close.compareTo(target) >= 0) {
                p.setTargetHit(true);
                p.setStopLossHit(false);
                return OutcomeStatus.TARGET_HIT;
            }
            if (stop != null && close.compareTo(stop) <= 0) {
                p.setStopLossHit(true);
                p.setTargetHit(false);
                return OutcomeStatus.STOP_LOSS_HIT;
            }
        } else {
            if (target != null && close.compareTo(target) <= 0) {
                p.setTargetHit(true);
                p.setStopLossHit(false);
                return OutcomeStatus.TARGET_HIT;
            }
            if (stop != null && close.compareTo(stop) >= 0) {
                p.setStopLossHit(true);
                p.setTargetHit(false);
                return OutcomeStatus.STOP_LOSS_HIT;
            }
        }
        p.setTargetHit(false);
        p.setStopLossHit(false);
        return OutcomeStatus.EXPIRED;
    }

    private static void computePnl(Prediction p, BigDecimal exitPrice, Direction dir) {
        BigDecimal entry = p.getEntryPrice();
        if (entry == null || entry.compareTo(BigDecimal.ZERO) <= 0 || exitPrice == null) {
            p.setActualPnlPct(null);
            return;
        }
        boolean longBias = dir == Direction.BUY || dir == Direction.BULLISH;
        boolean shortBias = dir == Direction.SELL || dir == Direction.BEARISH;
        if (!longBias && !shortBias) {
            p.setActualPnlPct(null);
            return;
        }
        BigDecimal pct;
        if (longBias) {
            pct = exitPrice.subtract(entry).divide(entry, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        } else {
            pct = entry.subtract(exitPrice).divide(entry, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
        p.setActualPnlPct(pct.setScale(4, RoundingMode.HALF_UP));
    }

    // ── First-touch helpers (package-private for unit testing) ──────────────

    /**
     * Pick a candle interval for first-touch detection. Trades off granularity
     * vs payload size:
     * <ul>
     *   <li>≤ 30-min window  → 1-minute candles (max 30 bars)</li>
     *   <li>≤ 4-hour window  → 5-minute candles (max 48 bars)</li>
     *   <li>longer           → 15-minute candles</li>
     * </ul>
     */
    static String pickInterval(int windowMinutes) {
        if (windowMinutes <= 30) return "1M";
        if (windowMinutes <= 240) return "5M";
        return "15M";
    }

    /**
     * Walk OHLC bars in chronological order and return the first bar whose
     * range crosses {@code target} or {@code stop}. Conservative tie-breaker:
     * when a single bar straddles both, return STOP_LOSS_HIT (the stop is
     * assumed to fire first because we have no intra-bar ordering data).
     *
     * <p>Always returns a result; {@link FirstTouchResult#isResolved()} is
     * false when no bar triggered. Window high/low are aggregated across all
     * scanned bars regardless.
     */
    static FirstTouchResult classifyFromBars(
            Direction dir,
            BigDecimal target,
            BigDecimal stop,
            List<Map<String, Object>> bars,
            boolean stopWinsOnSameBar) {

        boolean longBias = dir == Direction.BUY || dir == Direction.BULLISH;
        boolean shortBias = dir == Direction.SELL || dir == Direction.BEARISH;

        BigDecimal windowHigh = null;
        BigDecimal windowLow = null;

        for (Map<String, Object> bar : bars) {
            BigDecimal high = toBd(bar.get("high"));
            BigDecimal low = toBd(bar.get("low"));
            if (high == null || low == null) continue;
            windowHigh = windowHigh == null ? high : windowHigh.max(high);
            windowLow = windowLow == null ? low : windowLow.min(low);

            if (!longBias && !shortBias) continue;

            boolean targetTouched, stopTouched;
            if (longBias) {
                targetTouched = target != null && high.compareTo(target) >= 0;
                stopTouched = stop != null && low.compareTo(stop) <= 0;
            } else {
                targetTouched = target != null && low.compareTo(target) <= 0;
                stopTouched = stop != null && high.compareTo(stop) >= 0;
            }

            if (targetTouched && stopTouched) {
                if (stopWinsOnSameBar) {
                    return new FirstTouchResult(OutcomeStatus.STOP_LOSS_HIT, stop, windowHigh, windowLow);
                }
                return new FirstTouchResult(OutcomeStatus.TARGET_HIT, target, windowHigh, windowLow);
            }
            if (targetTouched) {
                return new FirstTouchResult(OutcomeStatus.TARGET_HIT, target, windowHigh, windowLow);
            }
            if (stopTouched) {
                return new FirstTouchResult(OutcomeStatus.STOP_LOSS_HIT, stop, windowHigh, windowLow);
            }
        }

        return new FirstTouchResult(null, null, windowHigh, windowLow);
    }

    /**
     * Outcome of walking the validity-window candles. {@link #status()} is
     * {@code null} when no bar triggered target/stop — the caller falls back
     * to close-only classification but can still use the captured window
     * high/low.
     */
    record FirstTouchResult(
            OutcomeStatus status,
            BigDecimal exitPrice,
            BigDecimal windowHigh,
            BigDecimal windowLow) {

        boolean isResolved() {
            return status != null;
        }
    }

    private static BigDecimal toBd(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
