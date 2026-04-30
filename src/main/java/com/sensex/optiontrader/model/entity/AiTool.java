package com.sensex.optiontrader.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "ai_tools")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AiTool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50, unique = true)
    private String name;

    @Column(nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false)
    private boolean enabled;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "tool", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @OrderBy("id ASC")
    private List<AiModel> models;
}
