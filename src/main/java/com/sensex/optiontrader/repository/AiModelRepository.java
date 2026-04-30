package com.sensex.optiontrader.repository;

import com.sensex.optiontrader.model.entity.AiModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AiModelRepository extends JpaRepository<AiModel, Long> {

    @Query("SELECT m FROM AiModel m JOIN FETCH m.tool WHERE m.isActive = TRUE")
    Optional<AiModel> findByIsActiveTrue();

    @Modifying
    @Query("UPDATE AiModel m SET m.isActive = FALSE WHERE m.isActive = TRUE")
    void deactivateAll();
}
