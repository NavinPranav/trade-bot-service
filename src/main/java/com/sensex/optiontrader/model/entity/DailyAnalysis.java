package com.sensex.optiontrader.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "daily_analysis")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DailyAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private LocalDate analysisDate;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String analysisData;

    @Column(nullable = false)
    private int predictionCount;

    @Column(nullable = false)
    private Instant createdAt;
}
