package com.sensex.optiontrader.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * One toggleable input the prediction pipeline can pass to Gemini (Phase 4.4).
 *
 * <p>The {@code is_required} flag locks a parameter ON: the admin UI renders
 * its toggle as disabled and the service-layer update path rejects any
 * request that tries to disable it. Required parameters are the ones the
 * model and the post-AI guardrails depend on — disabling them would either
 * remove input the AI cannot operate without (raw OHLCV, indicators) or
 * silently drop a safety check (multi-timeframe trend context).</p>
 *
 * <p>Source of truth lives in Postgres; the {@link com.sensex.optiontrader.service.AiParameterService}
 * pushes the current map to the ML service via {@code PUT /admin/parameters}
 * on startup and after every change.</p>
 */
@Entity
@Table(name = "ai_parameter_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AiParameterSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable machine-readable key (e.g. {@code "news_sentiment"}). */
    @Column(name = "parameter_key", nullable = false, unique = true, length = 100)
    private String parameterKey;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /** Required parameters cannot be disabled (UI toggle is locked, API rejects updates). */
    @Column(name = "is_required", nullable = false)
    @Builder.Default
    private boolean required = false;

    @Column(name = "display_name", nullable = false, length = 150)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;
}
