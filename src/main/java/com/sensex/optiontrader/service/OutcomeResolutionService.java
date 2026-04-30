package com.sensex.optiontrader.service;

import com.sensex.optiontrader.model.entity.Prediction;
import com.sensex.optiontrader.model.enums.OutcomeStatus;
import com.sensex.optiontrader.repository.PredictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutcomeResolutionService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final PredictionRepository predictionRepo;

    /**
     * Runs at 15:35 IST on weekdays — 5 minutes after market close.
     * Marks all same-day PENDING predictions as EXPIRED.
     * Actual price fields are left null and appear as '—' in the history table.
     */
    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    @Transactional
    public void resolveOutcomes() {
        LocalDate today = LocalDate.now(IST);
        List<Prediction> pending = predictionRepo.findPendingByDate(today);

        if (pending.isEmpty()) {
            log.debug("[OUTCOME] No pending predictions for {}", today);
            return;
        }

        Instant now = Instant.now();
        for (Prediction p : pending) {
            p.setOutcomeStatus(OutcomeStatus.EXPIRED);
            p.setOutcomeEvaluatedAt(now);
        }
        predictionRepo.saveAll(pending);
        log.info("[OUTCOME] Marked {} predictions as EXPIRED for {}", pending.size(), today);
    }
}
