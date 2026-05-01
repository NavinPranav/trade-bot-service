package com.sensex.optiontrader.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sensex.optiontrader.model.entity.DailyAnalysis;
import com.sensex.optiontrader.model.entity.Prediction;
import com.sensex.optiontrader.repository.DailyAnalysisRepository;
import com.sensex.optiontrader.repository.PredictionRepository;
import com.sensex.optiontrader.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DailyAnalysisService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final DailyAnalysisRepository dailyAnalysisRepo;
    private final PredictionRepository predictionRepo;
    private final PredictionService predictionService;
    private final NotificationService notificationService;
    private final UserRepository userRepo;
    private final ObjectMapper objectMapper;

    /**
     * Returns the latest daily analysis if the given user has not yet read it.
     * Returns empty if nothing exists or it was already dismissed.
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getUnreadForUser(Long userId) {
        Optional<DailyAnalysis> latest = dailyAnalysisRepo.findTopByOrderByAnalysisDateDesc();
        if (latest.isEmpty()) return Optional.empty();

        DailyAnalysis analysis = latest.get();
        if (dailyAnalysisRepo.countRead(userId, analysis.getId()) > 0) return Optional.empty();

        try {
            Map<String, Object> data = objectMapper.readValue(
                    analysis.getAnalysisData(),
                    new TypeReference<Map<String, Object>>() {});
            Map<String, Object> response = new LinkedHashMap<>(data);
            response.put("id", analysis.getId());
            response.put("analysisDate", analysis.getAnalysisDate().toString());
            response.put("predictionCount", analysis.getPredictionCount());
            return Optional.of(response);
        } catch (Exception e) {
            log.error("Failed to deserialize daily analysis id={}", analysis.getId(), e);
            return Optional.empty();
        }
    }

    @Transactional
    public void markRead(Long analysisId, Long userId) {
        dailyAnalysisRepo.markRead(userId, analysisId);
    }

    /**
     * Fetches today's predictions, calls the ML service for analysis, persists the result,
     * and pushes it to all connected users via WebSocket.
     *
     * Idempotent: skips if an analysis for today already exists.
     */
    @Transactional
    public void runDailyAnalysis() {
        LocalDate today = LocalDate.now(IST);

        if (dailyAnalysisRepo.findByAnalysisDate(today).isPresent()) {
            log.info("Daily analysis already exists for {} — skipping", today);
            return;
        }

        List<Prediction> predictions = predictionRepo.findByPredictionDateWithDetail(today);
        if (predictions.isEmpty()) {
            log.info("No predictions found for {} — skipping daily analysis", today);
            return;
        }

        log.info("Running daily analysis for {} ({} predictions)", today, predictions.size());

        List<Long> ids = predictions.stream().map(Prediction::getId).toList();
        Map<String, Object> result = predictionService.analysePredictions(ids, null, true);

        if (result.containsKey("error")) {
            log.warn("Daily analysis returned error for {}: {}", today, result.get("error"));
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(result);
            DailyAnalysis saved = dailyAnalysisRepo.save(
                    DailyAnalysis.builder()
                            .analysisDate(today)
                            .analysisData(json)
                            .predictionCount(predictions.size())
                            .createdAt(Instant.now())
                            .build());

            log.info("Daily analysis saved: id={} date={} predictions={}", saved.getId(), today, predictions.size());
            pushToConnectedUsers(saved, result);
        } catch (Exception e) {
            log.error("Failed to persist or broadcast daily analysis for {}", today, e);
        }
    }

    private void pushToConnectedUsers(DailyAnalysis analysis, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.put("id", analysis.getId());
        payload.put("analysisDate", analysis.getAnalysisDate().toString());
        payload.put("predictionCount", analysis.getPredictionCount());
        payload.put("type", "DAILY_ANALYSIS");

        List<String> emails = userRepo.findAllEmails();
        log.info("Broadcasting daily analysis to {} users", emails.size());
        for (String email : emails) {
            try {
                notificationService.sendDailyAnalysis(email, payload);
            } catch (Exception e) {
                log.warn("Could not push daily analysis to {}: {}", email, e.getMessage());
            }
        }
    }
}
