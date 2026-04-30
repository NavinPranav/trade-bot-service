package com.sensex.optiontrader.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "ai_models")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AiModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tool_id", nullable = false)
    private AiTool tool;

    @Column(nullable = false, length = 100)
    private String modelId;

    @Column(nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    @Builder.Default
    private boolean isActive = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
