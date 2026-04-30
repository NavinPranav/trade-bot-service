package com.sensex.optiontrader.repository;

import com.sensex.optiontrader.model.entity.AiPrompt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AiPromptRepository extends JpaRepository<AiPrompt, Long> {

    /** Load createdBy in-query; open-in-view is false so lazy access in AdminPromptController.toDto would fail. */
    @EntityGraph(attributePaths = "createdBy")
    Optional<AiPrompt> findByIsActiveTrue();

    @Modifying
    @Query("UPDATE AiPrompt p SET p.isActive = FALSE WHERE p.isActive = TRUE")
    void deactivateAll();

    @EntityGraph(attributePaths = "createdBy")
    Page<AiPrompt> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
