package com.sensex.optiontrader.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity @Table(name="market_snapshots") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MarketSnapshot {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private LocalDateTime timestamp;
    private BigDecimal sensexOpen; private BigDecimal sensexHigh; private BigDecimal sensexLow; private BigDecimal sensexClose;
    private Long sensexVolume; private BigDecimal indiaVix; private BigDecimal fiiNetBuy; private BigDecimal diiNetBuy; private BigDecimal usdInr;
}