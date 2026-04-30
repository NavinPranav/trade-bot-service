package com.sensex.optiontrader.repository;

import com.sensex.optiontrader.model.entity.AiPrompt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AiPromptRepository extends JpaRepository<AiPrompt, Long> {

    Optional<AiPrompt> findByIsActiveTrue();

    @Modifying
    @Query("UPDATE AiPrompt p SET p.isActive = FALSE WHERE p.isActive = TRUE")
    void deactivateAll();

    Page<AiPrompt> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
