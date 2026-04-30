package com.sensex.optiontrader.repository;

import com.sensex.optiontrader.model.entity.AiTool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AiToolRepository extends JpaRepository<AiTool, Long> {

    @Query("SELECT DISTINCT t FROM AiTool t LEFT JOIN FETCH t.models m ORDER BY t.id ASC")
    List<AiTool> findAllWithModels();
}
