package com.sensex.optiontrader.model.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PredictionResponse {
    private LocalDate predictionDate;
    private String horizon;
    private Direction direction;
    private BigDecimal magnitude;
    private BigDecimal confidence;
    private BigDecimal predictedVolatility;
    private BigDecimal currentSensex;
    private BigDecimal targetSensex;

    // ── Intra-day trading levels ──
    /** Ideal entry price for the trade. */
    private BigDecimal entryPrice;
    /** Stop-loss price — exit here if the trade goes wrong. */
    private BigDecimal stopLoss;
    /** Expected price when the trade target is reached. */
    private BigDecimal targetPrice;
    /** Reward-to-risk ratio (target_distance / stop_distance). */
    private BigDecimal riskReward;
    /** How many minutes this prediction remains valid. */
    private Integer validMinutes;

    // ── Signal metadata ──
    /** Epoch ms when this prediction was generated on the server. */
    private Long predictionTimestampMs;
    /** True when confidence < 65% — the system recommends not trading. */
    private Boolean noTradeZone;
    /** AI quota/rate-limit notice; non-null means the signal is a HOLD placeholder. */
    private String aiQuotaNotice;
    /** Natural-language rationale shown in the UI (display portion only, levels stripped). */
    private String predictionReason;

    // ── Legacy aliases kept for backward compatibility with older frontend code ──
    @JsonProperty("currentPrice")
    public BigDecimal getCurrentPrice() { return currentSensex; }

    @JsonProperty("targetPrice")
    public BigDecimal getTargetPrice() { return targetPrice != null ? targetPrice : targetSensex; }
}
