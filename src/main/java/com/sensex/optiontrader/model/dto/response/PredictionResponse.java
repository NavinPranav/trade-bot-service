package com.sensex.optiontrader.model.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sensex.optiontrader.model.enums.Direction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PredictionResponse {
    private LocalDate predictionDate;
    private String horizon;
    private Direction direction;
    private BigDecimal magnitude;
    private BigDecimal confidence;
    private BigDecimal predictedVolatility;
    private BigDecimal currentSensex;
    private BigDecimal targetSensex;
    /** User-visible notice when Gemini quota/rate limit forced a placeholder prediction. */
    private String aiQuotaNotice;
    /** Gemini (or future engines): why this direction/magnitude was chosen; shown in UI. */
    private String predictionReason;

    @JsonProperty("currentPrice")
    public BigDecimal getCurrentPrice() {
        return currentSensex;
    }

    @JsonProperty("targetPrice")
    public BigDecimal getTargetPrice() {
        return targetSensex;
    }
}