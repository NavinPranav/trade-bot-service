package com.sensex.optiontrader.repository;

import com.sensex.optiontrader.model.entity.Prediction;
import com.sensex.optiontrader.model.enums.OutcomeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PredictionRepository extends JpaRepository<Prediction, Long> {

    Optional<Prediction> findTopByHorizonOrderByPredictionDateDesc(String horizon);

    List<Prediction> findByPredictionDateBetweenOrderByPredictionDateDesc(LocalDate s, LocalDate e);

    @Query("SELECT COUNT(p) FROM Prediction p WHERE p.correct = true AND p.horizon = :h")
    long countCorrectByHorizon(String h);

    @Query("SELECT COUNT(p) FROM Prediction p WHERE p.correct IS NOT NULL AND p.horizon = :h")
    long countEvaluatedByHorizon(String h);

    // ── Deduplication: latest PENDING prediction for a user+horizon ──
    Optional<Prediction> findFirstByUser_IdAndHorizonAndOutcomeStatusOrderByPredictionTimestampDesc(
            Long userId, String horizon, OutcomeStatus outcomeStatus);

    // ── Paginated history with detail joined ──
    @Query("SELECT p FROM Prediction p LEFT JOIN FETCH p.detail WHERE p.user.id = :userId AND (:horizon IS NULL OR p.horizon = :horizon) ORDER BY p.predictionTimestamp DESC")
    Page<Prediction> findHistoryByUser(@Param("userId") Long userId, @Param("horizon") String horizon, Pageable pageable);

    // ── Outcome resolution: all PENDING predictions for a given trade date ──
    @Query("SELECT p FROM Prediction p JOIN FETCH p.user WHERE p.outcomeStatus = 'PENDING' AND p.predictionTimestamp IS NOT NULL AND p.predictionDate = :date")
    List<Prediction> findPendingByDate(@Param("date") LocalDate date);

    // ── Aggregate metrics for the summary banner ──
    @Query("SELECT COUNT(p) FROM Prediction p WHERE p.user.id = :userId AND (:horizon IS NULL OR p.horizon = :horizon)")
    long countByUserAndHorizon(@Param("userId") Long userId, @Param("horizon") String horizon);

    @Query("SELECT COUNT(p) FROM Prediction p WHERE p.user.id = :userId AND (:horizon IS NULL OR p.horizon = :horizon) AND p.outcomeStatus <> 'PENDING'")
    long countResolvedByUserAndHorizon(@Param("userId") Long userId, @Param("horizon") String horizon);

    @Query("SELECT COUNT(p) FROM Prediction p WHERE p.user.id = :userId AND (:horizon IS NULL OR p.horizon = :horizon) AND p.targetHit = true")
    long countTargetHitsByUserAndHorizon(@Param("userId") Long userId, @Param("horizon") String horizon);

    @Query("SELECT COUNT(p) FROM Prediction p WHERE p.user.id = :userId AND (:horizon IS NULL OR p.horizon = :horizon) AND p.stopLossHit = true")
    long countStopLossHitsByUserAndHorizon(@Param("userId") Long userId, @Param("horizon") String horizon);

    @Query("SELECT AVG(p.confidence) FROM Prediction p WHERE p.user.id = :userId AND (:horizon IS NULL OR p.horizon = :horizon) AND p.confidence IS NOT NULL")
    Double avgConfidenceByUserAndHorizon(@Param("userId") Long userId, @Param("horizon") String horizon);

    @Query("SELECT AVG(p.riskReward) FROM Prediction p WHERE p.user.id = :userId AND (:horizon IS NULL OR p.horizon = :horizon) AND p.riskReward IS NOT NULL")
    Double avgRiskRewardByUserAndHorizon(@Param("userId") Long userId, @Param("horizon") String horizon);
}
