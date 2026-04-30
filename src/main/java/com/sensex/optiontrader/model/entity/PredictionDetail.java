package com.sensex.optiontrader.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "prediction_details")
@Getter
@Setter
@NoArgsConstructor
public class PredictionDetail {

    @Id
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "id")
    private Prediction prediction;

    @Column(columnDefinition = "TEXT")
    private String predictionReason;

    @Column(length = 500)
    private String aiQuotaNotice;
}
