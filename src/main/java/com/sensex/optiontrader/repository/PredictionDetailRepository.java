package com.sensex.optiontrader.repository;

import com.sensex.optiontrader.model.entity.PredictionDetail;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PredictionDetailRepository extends JpaRepository<PredictionDetail, Long> {
}
