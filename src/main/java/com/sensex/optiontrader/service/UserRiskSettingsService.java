package com.sensex.optiontrader.service;

import com.sensex.optiontrader.model.dto.request.UserRiskSettingsRequest;
import com.sensex.optiontrader.model.dto.response.UserRiskSettingsResponse;
import com.sensex.optiontrader.model.entity.User;
import com.sensex.optiontrader.model.enums.Direction;
import com.sensex.optiontrader.repository.PredictionRepository;
import com.sensex.optiontrader.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserRiskSettingsService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final List<Direction> DIRECTIONAL = List.of(
            Direction.BUY, Direction.SELL, Direction.BULLISH, Direction.BEARISH);

    private final UserRepository userRepo;
    private final PredictionRepository predictionRepo;
    private final CacheManager cacheManager;

    @Transactional(readOnly = true)
    public UserRiskSettingsResponse getForUser(Long userId) {
        User u = userRepo.findById(userId).orElseThrow();
        LocalDate today = LocalDate.now(IST);
        long dirCount = predictionRepo.countDirectionalSignalsOnDate(userId, today, DIRECTIONAL);
        BigDecimal pnlSum = predictionRepo.sumResolvedPnlPctOnDate(userId, today);
        if (pnlSum == null) {
            pnlSum = BigDecimal.ZERO;
        }
        return UserRiskSettingsResponse.builder()
                .tradingHalted(Boolean.TRUE.equals(u.getRiskTradingHalted()))
                .maxSignalsPerDay(u.getRiskMaxSignalsPerDay())
                .maxDailyLossPct(u.getRiskMaxDailyLossPct())
                .directionalSignalsToday(dirCount)
                .resolvedPnlSumTodayPct(pnlSum)
                .build();
    }

    @Transactional
    public UserRiskSettingsResponse update(Long userId, UserRiskSettingsRequest req) {
        User u = userRepo.findById(userId).orElseThrow();
        if (req.getTradingHalted() == null) {
            throw new IllegalArgumentException("tradingHalted is required");
        }
        u.setRiskTradingHalted(req.getTradingHalted());
        if (req.getMaxSignalsPerDay() != null && req.getMaxSignalsPerDay() > 0) {
            u.setRiskMaxSignalsPerDay(req.getMaxSignalsPerDay());
        } else {
            u.setRiskMaxSignalsPerDay(null);
        }
        BigDecimal loss = req.getMaxDailyLossPct();
        if (loss != null && loss.compareTo(BigDecimal.ZERO) > 0) {
            u.setRiskMaxDailyLossPct(loss);
        } else {
            u.setRiskMaxDailyLossPct(null);
        }
        userRepo.save(u);
        evictPredictionCaches();
        log.info("[RISK] user={} updated risk settings", userId);
        return getForUser(userId);
    }

    private void evictPredictionCaches() {
        try {
            var c = cacheManager.getCache("predictions");
            if (c != null) {
                c.clear();
            }
        } catch (Exception e) {
            log.debug("Could not evict predictions cache: {}", e.getMessage());
        }
    }
}
