package com.sensex.optiontrader.service;

import com.sensex.optiontrader.config.AngelOneProperties.InstrumentToken;
import com.sensex.optiontrader.integration.MarketDataProvider;
import com.sensex.optiontrader.integration.angelone.LiveTickData;
import com.sensex.optiontrader.model.entity.Prediction;
import com.sensex.optiontrader.model.enums.Direction;
import com.sensex.optiontrader.model.enums.OutcomeStatus;
import com.sensex.optiontrader.repository.PredictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutcomeResolutionService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Matches ML coerce default when DB row has null valid_minutes. */
    private static final int DEFAULT_VALID_MINUTES = 15;

    private final PredictionRepository predictionRepo;
    private final MarketDataProvider marketDataProvider;
    private final InstrumentRegistry instrumentRegistry;

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

        Optional<String> tokenOpt = resolveToken(p);
        if (tokenOpt.isPresent()) {
            LiveTickData tick = marketDataProvider.getLatestTick(tokenOpt.get());
            if (tick != null) {
                if (tick.getHighPrice() > 0) {
                    p.setActualHighPrice(BigDecimal.valueOf(tick.getHighPrice()).setScale(2, RoundingMode.HALF_UP));
                }
                if (tick.getLowPrice() > 0) {
                    p.setActualLowPrice(BigDecimal.valueOf(tick.getLowPrice()).setScale(2, RoundingMode.HALF_UP));
                }
            }
        }

        Direction dir = p.getDirection();
        OutcomeStatus status = classifyOutcome(p, closeBd, dir);
        p.setOutcomeStatus(status);
        p.setOutcomeEvaluatedAt(evaluatedAt);
        computePnl(p, closeBd, dir);
        return true;
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
     * Classify using <strong>closing price vs levels</strong> (simple v1; does not simulate intraday path order).
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

    private static void computePnl(Prediction p, BigDecimal close, Direction dir) {
        BigDecimal entry = p.getEntryPrice();
        if (entry == null || entry.compareTo(BigDecimal.ZERO) <= 0) {
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
            pct = close.subtract(entry).divide(entry, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        } else {
            pct = entry.subtract(close).divide(entry, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
        p.setActualPnlPct(pct.setScale(4, RoundingMode.HALF_UP));
    }
}
