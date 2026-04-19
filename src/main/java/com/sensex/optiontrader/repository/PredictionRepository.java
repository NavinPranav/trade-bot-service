package com.sensex.optiontrader.repository;
import com.sensex.optiontrader.model.entity.Prediction;
import org.springframework.data.jpa.repository.*;
import java.time.LocalDate;
import java.util.*;
public interface PredictionRepository extends JpaRepository<Prediction,Long> {
    Optional<Prediction> findTopByHorizonOrderByPredictionDateDesc(String horizon);
    List<Prediction> findByPredictionDateBetweenOrderByPredictionDateDesc(LocalDate s, LocalDate e);
    @Query("SELECT COUNT(p) FROM Prediction p WHERE p.correct=true AND p.horizon=:h") long countCorrectByHorizon(String h);
    @Query("SELECT COUNT(p) FROM Prediction p WHERE p.correct IS NOT NULL AND p.horizon=:h") long countEvaluatedByHorizon(String h);
}