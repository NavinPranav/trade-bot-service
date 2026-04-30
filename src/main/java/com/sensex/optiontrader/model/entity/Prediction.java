package com.sensex.optiontrader.model.entity;

import com.sensex.optiontrader.model.enums.Direction;
import com.sensex.optiontrader.model.enums.OutcomeStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "predictions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Prediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private LocalDate predictionDate;

    /** Exact UTC instant the ML service generated this signal. */
    private Instant predictionTimestamp;

    /** Angel One instrument token active at prediction time. */
    @Column(length = 20)
    private String instrumentToken;

    @Column(nullable = false)
    private String horizon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @Column(precision = 6, scale = 4)
    private BigDecimal magnitude;

    @Column(precision = 5, scale = 2)
    private BigDecimal confidence;

    @Column(precision = 6, scale = 4)
    private BigDecimal predictedVolatility;

    // ── Trading levels captured at prediction time ──
    @Column(precision = 10, scale = 2)
    private BigDecimal currentSensex;

    @Column(precision = 10, scale = 2)
    private BigDecimal entryPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal stopLoss;

    @Column(precision = 10, scale = 2)
    private BigDecimal targetSensex;

    @Column(precision = 5, scale = 2)
    private BigDecimal riskReward;

    private Integer validMinutes;

    private Boolean noTradeZone;

    // ── Outcome resolution fields (filled after validity window closes) ──
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @Builder.Default
    private OutcomeStatus outcomeStatus = OutcomeStatus.PENDING;

    @Column(precision = 10, scale = 2)
    private BigDecimal actualClosePrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal actualHighPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal actualLowPrice;

    private Boolean stopLossHit;

    private Boolean targetHit;

    @Column(precision = 6, scale = 4)
    private BigDecimal actualPnlPct;

    private Instant outcomeEvaluatedAt;

    // ── Legacy fields kept for backward compatibility ──
    @Enumerated(EnumType.STRING)
    private Direction actualDirection;

    @Column(precision = 6, scale = 4)
    private BigDecimal actualMagnitude;

    private Boolean correct;

    private LocalDateTime createdAt;

    @OneToOne(mappedBy = "prediction", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private PredictionDetail detail;
}
