package com.sensex.optiontrader.model.entity;

import com.sensex.optiontrader.model.enums.UserRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity @Table(name="users") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false, unique=true) private String email;
    @Column(nullable=false) private String password;
    @Column(nullable=false) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable=false) @Builder.Default private UserRole role = UserRole.USER;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "preferred_instrument_id", nullable = false)
    private Instrument preferredInstrument;

    /** Manual kill switch — directional model output is forced to HOLD. */
    @Column(name = "risk_trading_halted", nullable = false)
    @Builder.Default
    private Boolean riskTradingHalted = false;

    /** Max directional predictions per IST day; null = unlimited. */
    @Column(name = "risk_max_signals_per_day")
    private Integer riskMaxSignalsPerDay;

    /**
     * Daily loss ceiling using sum of {@code actualPnlPct} for resolved rows today (same IST date).
     * Positive number e.g. 2.5 means block when sum &lt;= -2.5. Null = unlimited.
     */
    @Column(name = "risk_max_daily_loss_pct", precision = 10, scale = 4)
    private BigDecimal riskMaxDailyLossPct;

    @CreationTimestamp private LocalDateTime createdAt;
    @UpdateTimestamp private LocalDateTime updatedAt;
}