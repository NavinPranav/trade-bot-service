package com.sensex.optiontrader.service;

import com.sensex.optiontrader.model.dto.response.PredictionResponse;
import com.sensex.optiontrader.model.entity.Prediction;
import com.sensex.optiontrader.model.entity.PredictionDetail;
import com.sensex.optiontrader.model.entity.User;
import com.sensex.optiontrader.model.enums.Direction;
import com.sensex.optiontrader.model.enums.OutcomeStatus;
import com.sensex.optiontrader.repository.PredictionDetailRepository;
import com.sensex.optiontrader.repository.PredictionRepository;
import com.sensex.optiontrader.repository.UserRepository;
import com.sensex.optiontrader.service.AiModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionPersistenceService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final PredictionRepository predictionRepo;
    private final PredictionDetailRepository detailRepo;
    private final UserRepository userRepo;
    private final InstrumentRegistry instrumentRegistry;
    private final AiModelService aiModelService;

    /**
     * Persists a new prediction only if the previous record for the same user+horizon
     * has exceeded its validity window, preventing duplicate rows for every tick cycle.
     */
    @Transactional
    public void maybePersist(Long userId, String horizon, PredictionResponse result) {
        if (isErrorPlaceholder(result)) {
            return;
        }
        if (isWithinExistingWindow(userId, horizon)) {
            log.debug("[PERSIST] Skipping — user={} horizon={} still within validity window", userId, horizon);
            return;
        }

        Instant predictionTimestamp = result.getPredictionTimestampMs() != null
                ? Instant.ofEpochMilli(result.getPredictionTimestampMs())
                : Instant.now();

        User user = userRepo.getReferenceById(userId);

        String token = instrumentRegistry.primaryTokenOrNone(userId);

        Direction direction = result.getDirection() != null ? result.getDirection() : Direction.HOLD;

        Prediction prediction = Prediction.builder()
                .user(user)
                .predictionDate(LocalDate.ofInstant(predictionTimestamp, IST))
                .predictionTimestamp(predictionTimestamp)
                .instrumentToken(token.isBlank() ? null : token)
                .horizon(horizon)
                .direction(direction)
                .magnitude(result.getMagnitude())
                .confidence(result.getConfidence())
                .predictedVolatility(result.getPredictedVolatility())
                .currentSensex(result.getCurrentSensex())
                .entryPrice(result.getEntryPrice())
                .stopLoss(result.getStopLoss())
                .targetSensex(result.getTargetSensex() != null ? result.getTargetSensex() : result.getTargetPrice())
                .riskReward(result.getRiskReward())
                .validMinutes(result.getValidMinutes())
                .noTradeZone(result.getNoTradeZone())
                .outcomeStatus(OutcomeStatus.PENDING)
                .aiTool(aiModelService.getActiveToolName())
                .aiModel(aiModelService.getActiveModelId())
                .createdAt(LocalDateTime.now(IST))
                .build();

        predictionRepo.save(prediction);

        if (hasText(result.getPredictionReason()) || hasText(result.getAiQuotaNotice())) {
            PredictionDetail detail = new PredictionDetail();
            detail.setPrediction(prediction);
            detail.setPredictionReason(result.getPredictionReason());
            detail.setAiQuotaNotice(result.getAiQuotaNotice());
            detailRepo.save(detail);
        }

        log.info("[PERSIST] Saved prediction id={} user={} horizon={} direction={} confidence={}",
                prediction.getId(), userId, horizon, direction, result.getConfidence());
    }

    private boolean isWithinExistingWindow(Long userId, String horizon) {
        return predictionRepo
                .findFirstByUser_IdAndHorizonAndOutcomeStatusOrderByPredictionTimestampDesc(
                        userId, horizon, OutcomeStatus.PENDING)
                .map(p -> {
                    if (p.getPredictionTimestamp() == null || p.getValidMinutes() == null) {
                        return false;
                    }
                    Instant windowEnd = p.getPredictionTimestamp().plusSeconds(p.getValidMinutes() * 60L);
                    return Instant.now().isBefore(windowEnd);
                })
                .orElse(false);
    }

    /** Error placeholders from the fallback branch have zero confidence and no trading levels. */
    private static boolean isErrorPlaceholder(PredictionResponse result) {
        return result.getConfidence() == null
                && result.getEntryPrice() == null
                && result.getCurrentSensex() == null;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
