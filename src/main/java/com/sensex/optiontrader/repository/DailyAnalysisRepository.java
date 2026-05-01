package com.sensex.optiontrader.repository;

import com.sensex.optiontrader.model.entity.DailyAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface DailyAnalysisRepository extends JpaRepository<DailyAnalysis, Long> {

    Optional<DailyAnalysis> findTopByOrderByAnalysisDateDesc();

    Optional<DailyAnalysis> findByAnalysisDate(LocalDate date);

    @Query(value = "SELECT COUNT(*) FROM daily_analysis_reads WHERE user_id = :userId AND daily_analysis_id = :analysisId",
            nativeQuery = true)
    long countRead(@Param("userId") Long userId, @Param("analysisId") Long analysisId);

    @Modifying
    @Query(value = "INSERT INTO daily_analysis_reads (user_id, daily_analysis_id, read_at) " +
            "VALUES (:userId, :analysisId, NOW()) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    void markRead(@Param("userId") Long userId, @Param("analysisId") Long analysisId);
}
