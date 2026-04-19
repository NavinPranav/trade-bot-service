package com.sensex.optiontrader.model.entity;

import com.sensex.optiontrader.model.enums.Direction;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.*;

@Entity @Table(name="predictions") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Prediction {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private LocalDate predictionDate;
    @Column(nullable=false) private String horizon;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private Direction direction;
    @Column(precision=6,scale=4) private BigDecimal magnitude;
    @Column(precision=5,scale=2) private BigDecimal confidence;
    @Column(precision=6,scale=4) private BigDecimal predictedVolatility;
    @Enumerated(EnumType.STRING) private Direction actualDirection;
    @Column(precision=6,scale=4) private BigDecimal actualMagnitude;
    private Boolean correct;
    private LocalDateTime createdAt;
}