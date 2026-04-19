package com.sensex.optiontrader.model.entity;

import com.sensex.optiontrader.model.enums.*;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.*;

@Entity @Table(name="positions") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Position {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="user_id",nullable=false) private User user;
    @Enumerated(EnumType.STRING) private StrategyType strategyType;
    @Enumerated(EnumType.STRING) private OptionType optionType;
    @Column(nullable=false) private BigDecimal strikePrice;
    @Column(nullable=false) private LocalDate expiry;
    @Column(nullable=false) private Integer lots;
    @Column(nullable=false) private BigDecimal entryPrice;
    private BigDecimal exitPrice; private BigDecimal currentPrice;
    @Column(nullable=false) @Builder.Default private Boolean isOpen = true;
    private BigDecimal delta; private BigDecimal gamma; private BigDecimal theta; private BigDecimal vega;
    private LocalDateTime openedAt; private LocalDateTime closedAt;
}