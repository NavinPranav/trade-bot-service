package com.sensex.optiontrader.model.entity;

import com.sensex.optiontrader.model.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.*;
import java.util.Map;

@Entity @Table(name="backtest_jobs") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BacktestJob {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="user_id",nullable=false) private User user;
    @Enumerated(EnumType.STRING) private StrategyType strategyType;
    private LocalDate startDate; private LocalDate endDate;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") private Map<String,Object> parameters;
    @Enumerated(EnumType.STRING) @Builder.Default private BacktestStatus status = BacktestStatus.PENDING;
    private Integer progressPercent;
    private Double sharpeRatio; private Double maxDrawdown; private Double winRate; private Double totalReturn; private Integer totalTrades;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") private Map<String,Object> resultDetails;
    private LocalDateTime createdAt; private LocalDateTime completedAt;
}