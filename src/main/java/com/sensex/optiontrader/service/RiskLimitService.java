package com.sensex.optiontrader.service;

import com.sensex.optiontrader.model.dto.response.PredictionResponse;
import com.sensex.optiontrader.model.entity.User;
import com.sensex.optiontrader.model.enums.Direction;
import com.sensex.optiontrader.repository.PredictionRepository;
import com.sensex.optiontrader.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Enforces per-user signal budgets and loss ceilings before persisting or showing directional calls.
 * This is not broker-level risk — it gates what the app recommends.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskLimitService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final List<Direction> DIRECTIONAL = List.of(
            Direction.BUY, Direction.SELL, Direction.BULLISH, Direction.BEARISH);

    private final UserRepository userRepo;
    private final PredictionRepository predictionRepo;

    public boolean isDirectional(Direction d) {
        if (d == null) {
            return false;
        }
        return DIRECTIONAL.contains(d);
    }

    /**
     * @return empty if directional trading is allowed; otherwise a short user-facing reason.
     */
    public Optional<String> blockReasonForUser(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        User u = userRepo.findById(userId).orElse(null);
        if (u == null) {
            return Optional.empty();
        }
        if (Boolean.TRUE.equals(u.getRiskTradingHalted())) {
            return Optional.of("Trading is halted (kill switch) for your account. Signals show HOLD only.");
        }
        LocalDate today = LocalDate.now(IST);

        Integer maxSig = u.getRiskMaxSignalsPerDay();
        if (maxSig != null && maxSig > 0) {
            long n = predictionRepo.countDirectionalSignalsOnDate(userId, today, DIRECTIONAL);
            if (n >= maxSig) {
                return Optional.of("Daily directional signal limit reached (" + maxSig + " per day).");
            }
        }

        BigDecimal maxLoss = u.getRiskMaxDailyLossPct();
        if (maxLoss != null && maxLoss.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal sum = predictionRepo.sumResolvedPnlPctOnDate(userId, today);
            if (sum == null) {
                sum = BigDecimal.ZERO;
            }
            BigDecimal floor = maxLoss.negate();
            if (sum.compareTo(floor) <= 0) {
                return Optional.of(String.format(
                        "Daily loss limit reached (resolved P&L sum today: %s%%, limit: -%s%%).",
                        sum.stripTrailingZeros().toPlainString(),
                        maxLoss.stripTrailingZeros().toPlainString()));
            }
        }
        return Optional.empty();
    }

    public PredictionResponse applyRiskLimits(PredictionResponse raw, Long userId) {
        if (raw == null || !isDirectional(raw.getDirection())) {
            return raw;
        }
        Optional<String> reason = blockReasonForUser(userId);
        if (reason.isEmpty()) {
            return raw;
        }
        log.warn("[RISK] user={} directional {} clamped to HOLD: {}", userId, raw.getDirection(), reason.get());
        return clampToHold(raw, reason.get());
    }

    private static PredictionResponse clampToHold(PredictionResponse r, String notice) {
        String pr = r.getPredictionReason();
        String suffix = "\n\n[Risk limit] " + notice;
        String newReason = (pr == null || pr.isBlank()) ? suffix.trim() : pr + suffix;

        PredictionResponse.PredictionResponseBuilder b = PredictionResponse.builder()
                .predictionDate(r.getPredictionDate())
                .predictionTimestampMs(r.getPredictionTimestampMs() != null
                        ? r.getPredictionTimestampMs()
                        : System.currentTimeMillis())
                .horizon(r.getHorizon())
                .direction(Direction.HOLD)
                .magnitude(BigDecimal.ZERO)
                .confidence(r.getConfidence())
                .predictedVolatility(r.getPredictedVolatility())
                .currentSensex(r.getCurrentSensex())
                .targetSensex(r.getTargetSensex())
                .entryPrice(r.getEntryPrice())
                .stopLoss(r.getStopLoss())
                .targetPrice(r.getTargetPrice())
                .riskReward(r.getRiskReward())
                .validMinutes(r.getValidMinutes())
                .noTradeZone(true)
                .predictionReason(newReason)
                .riskLimitNotice(notice);
        if (r.getAiQuotaNotice() != null && !r.getAiQuotaNotice().isBlank()) {
            b.aiQuotaNotice(r.getAiQuotaNotice());
        }
        return b.build();
    }
}
