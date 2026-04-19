package com.sensex.optiontrader.model.entity;

import com.sensex.optiontrader.model.enums.AlertType;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity @Table(name="alerts") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Alert {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="user_id",nullable=false) private User user;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private AlertType alertType;
    private BigDecimal threshold; private String description;
    @Builder.Default private Boolean active = true;
    private Boolean triggered; private LocalDateTime triggeredAt; private LocalDateTime createdAt;
}