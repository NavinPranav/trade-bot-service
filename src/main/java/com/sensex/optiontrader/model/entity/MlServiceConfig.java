package com.sensex.optiontrader.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ml_service_config")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MlServiceConfig {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, unique = true, length = 100)
    private String configKey;

    @Column(name = "config_value", nullable = false, length = 500)
    private String configValue;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
