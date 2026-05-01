package com.sensex.optiontrader.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the end-of-day prediction analysis after market close.
 *
 * Trigger: 16:00 IST on weekdays (market closes at 15:30; outcome resolver runs at 15:35,
 * giving outcomes time to settle before the analysis snapshot is taken).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyAnalysisScheduler {

    private final DailyAnalysisService dailyAnalysisService;

    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Kolkata")
    public void runDailyAnalysis() {
        log.info("Daily analysis scheduler triggered (16:00 IST)");
        try {
            dailyAnalysisService.runDailyAnalysis();
        } catch (Exception e) {
            log.error("Daily analysis scheduler failed", e);
        }
    }
}
