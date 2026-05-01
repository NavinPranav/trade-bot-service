package com.sensex.optiontrader.repository;

import com.sensex.optiontrader.model.entity.Prediction;
import com.sensex.optiontrader.model.enums.Direction;
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

    // ── Paginated history with detail + user joined ──
    @Query("SELECT p FROM Prediction p LEFT JOIN FETCH p.detail JOIN FETCH p.user WHERE p.user.id = :userId "
            + "AND (:horizonAll = true OR p.horizon IN :horizons) "
            + "AND (:directionAll = true OR p.direction IN :directions) "
            + "ORDER BY p.predictionTimestamp DESC")
    Page<Prediction> findHistoryByUser(
            @Param("userId") Long userId,
            @Param("horizonAll") boolean horizonAll,
            @Param("horizons") List<String> horizons,
            @Param("directionAll") boolean directionAll,
            @Param("directions") List<Direction> directions,
            Pageable pageable);

    /** All users’ predictions (same ordering as per-user history). */
    @Query("SELECT p FROM Prediction p LEFT JOIN FETCH p.detail JOIN FETCH p.user WHERE "
            + "(:horizonAll = true OR p.horizon IN :horizons) "
            + "AND (:directionAll = true OR p.direction IN :directions) "
            + "ORDER BY p.predictionTimestamp DESC")
    Page<Prediction> findHistoryAll(
            @Param("horizonAll") boolean horizonAll,
            @Param("horizons") List<String> horizons,
            @Param("directionAll") boolean directionAll,
            @Param("directions") List<Direction> directions,
            Pageable pageable);

    // ── Outcome resolution: all PENDING predictions for a given trade date ──
    @Query("SELECT p FROM Prediction p JOIN FETCH p.user WHERE p.outcomeStatus = 'PENDING' AND p.predictionTimestamp IS NOT NULL AND p.predictionDate = :date")
    List<Prediction> findPendingByDate(@Param("date") LocalDate date);

    /** PENDING rows with a timestamp (validity window can be evaluated). */
    @Query("SELECT p FROM Prediction p JOIN FETCH p.user WHERE p.outcomeStatus = 'PENDING' AND p.predictionTimestamp IS NOT NULL")
    List<Prediction> findPendingForOutcomeResolution();

    /** All predictions for a given trade date with detail + user eagerly fetched (for daily analysis scheduler). */
    @Query("SELECT p FROM Prediction p LEFT JOIN FETCH p.detail JOIN FETCH p.user WHERE p.predictionDate = :date")
    List<Prediction> findByPredictionDateWithDetail(@Param("date") LocalDate date);

    // ── Aggregate metrics for the summary banner ──
    @Query("SELECT COUNT(p) FROM Prediction p WHERE p.user.id = :userId "
            + "AND (:horizonAll = true OR p.horizon IN :horizons) "
            + "AND (:directionAll = true OR p.direction IN :directions)")
    long countByUserAndHorizon(
            @Param("userId") Long userId,
            @Param("horizonAll") boolean horizonAll,
            @Param("horizons") List<String> horizons,
            @Param("directionAll") boolean directionAll,
            @Param("directions") List<Direction> directions);

    @Query("SELECT COUNT(p) FROM Prediction p WHERE p.user.id = :userId "
            + "AND (:horizonAll = true OR p.horizon IN :horizons) "
            + "AND (:directionAll = true OR p.direction IN :directions) "
            + "AND p.outcomeStatus <> 'PENDING'")
    long countResolvedByUserAndHorizon(
            @Param("userId") Long userId,
            @Param("horizonAll") boolean horizonAll,
            @Param("horizons") List<String> horizons,
            @Param("directionAll") boolean directionAll,
            @Param("directions") List<Direction> directions);

    @Query("SELECT COUNT(p) FROM Prediction p WHERE p.user.id = :userId "
            + "AND (:horizonAll = true OR p.horizon IN :horizons) "
            + "AND (:directionAll = true OR p.direction IN :directions) "
            + "AND p.targetHit = true")
    long countTargetHitsByUserAndHorizon(
            @Param("userId") Long userId,
            @Param("horizonAll") boolean horizonAll,
            @Param("horizons") List<String> horizons,
            @Param("directionAll") boolean directionAll,
            @Param("directions") List<Direction> directions);

    @Query("SELECT COUNT(p) FROM Prediction p WHERE p.user.id = :userId "
            + "AND (:horizonAll = true OR p.horizon IN :horizons) "
            + "AND (:directionAll = true OR p.direction IN :directions) "
            + "AND p.stopLossHit = true")
    long countStopLossHitsByUserAndHorizon(
            @Param("userId") Long userId,
            @Param("horizonAll") boolean horizonAll,
            @Param("horizons") List<String> horizons,
            @Param("directionAll") boolean directionAll,
            @Param("directions") List<Direction> directions);

    @Query("SELECT AVG(p.confidence) FROM Prediction p WHERE p.user.id = :userId "
            + "AND (:horizonAll = true OR p.horizon IN :horizons) "
            + "AND (:directionAll = true OR p.direction IN :directions) "
            + "AND p.confidence IS NOT NULL")
    Double avgConfidenceByUserAndHorizon(
            @Param("userId") Long userId,
            @Param("horizonAll") boolean horizonAll,
            @Param("horizons") List<String> horizons,
            @Param("directionAll") boolean directionAll,
            @Param("directions") List<Direction> directions);

    @Query("SELECT AVG(p.riskReward) FROM Prediction p WHERE p.user.id = :userId "
            + "AND (:horizonAll = true OR p.horizon IN :horizons) "
            + "AND (:directionAll = true OR p.direction IN :directions) "
            + "AND p.riskReward IS NOT NULL")
    Double avgRiskRewardByUserAndHorizon(
            @Param("userId") Long userId,
            @Param("horizonAll") boolean horizonAll,
            @Param("horizons") List<String> horizons,
            @Param("directionAll") boolean directionAll,
            @Param("directions") List<Direction> directions);

    // ── Aggregate metrics (platform-wide, optional horizon / direction) ──
    @Query("SELECT COUNT(p) FROM Prediction p WHERE "
            + "(:horizonAll = true OR p.horizon IN :horizons) "
            + "AND (:directionAll = true OR p.direction IN :directions)")
    long countAllByHorizon(
            @Param("horizonAll") boolean horizonAll,
            @Param("horizons") List<String> horizons,
            @Param("directionAll") boolean directionAll,
            @Param("directions") List<Direction> directions);

    @Query("SELECT COUNT(p) FROM Prediction p WHERE "
            + "(:horizonAll = true OR p.horizon IN :horizons) "
            + "AND (:directionAll = true OR p.direction IN :directions) "
            + "AND p.outcomeStatus <> 'PENDING'")
    long countResolvedAllByHorizon(
            @Param("horizonAll") boolean horizonAll,
            @Param("horizons") List<String> horizons,
            @Param("directionAll") boolean directionAll,
            @Param("directions") List<Direction> directions);

    @Query("SELECT COUNT(p) FROM Prediction p WHERE "
            + "(:horizonAll = true OR p.horizon IN :horizons) "
            + "AND (:directionAll = true OR p.direction IN :directions) "
            + "AND p.targetHit = true")
    long countTargetHitsAllByHorizon(
            @Param("horizonAll") boolean horizonAll,
            @Param("horizons") List<String> horizons,
            @Param("directionAll") boolean directionAll,
            @Param("directions") List<Direction> directions);

    @Query("SELECT COUNT(p) FROM Prediction p WHERE "
            + "(:horizonAll = true OR p.horizon IN :horizons) "
            + "AND (:directionAll = true OR p.direction IN :directions) "
            + "AND p.stopLossHit = true")
    long countStopLossHitsAllByHorizon(
            @Param("horizonAll") boolean horizonAll,
            @Param("horizons") List<String> horizons,
            @Param("directionAll") boolean directionAll,
            @Param("directions") List<Direction> directions);

    @Query("SELECT AVG(p.confidence) FROM Prediction p WHERE "
            + "(:horizonAll = true OR p.horizon IN :horizons) "
            + "AND (:directionAll = true OR p.direction IN :directions) "
            + "AND p.confidence IS NOT NULL")
    Double avgConfidenceAllByHorizon(
            @Param("horizonAll") boolean horizonAll,
            @Param("horizons") List<String> horizons,
            @Param("directionAll") boolean directionAll,
            @Param("directions") List<Direction> directions);

    @Query("SELECT AVG(p.riskReward) FROM Prediction p WHERE "
            + "(:horizonAll = true OR p.horizon IN :horizons) "
            + "AND (:directionAll = true OR p.direction IN :directions) "
            + "AND p.riskReward IS NOT NULL")
    Double avgRiskRewardAllByHorizon(
            @Param("horizonAll") boolean horizonAll,
            @Param("horizons") List<String> horizons,
            @Param("directionAll") boolean directionAll,
            @Param("directions") List<Direction> directions);
}
