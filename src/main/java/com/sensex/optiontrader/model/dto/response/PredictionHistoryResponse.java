package com.sensex.optiontrader.model.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PredictionHistoryResponse {
    private Long id;
    private LocalDate predictionDate;
    private Instant predictionTimestamp;
    private String horizon;
    private String direction;
    private BigDecimal confidence;
    private BigDecimal predictedVolatility;
    private BigDecimal currentSensex;
    private BigDecimal entryPrice;
    private BigDecimal stopLoss;
    private BigDecimal targetSensex;
    private BigDecimal riskReward;
    private Boolean noTradeZone;
    private String outcomeStatus;
    private BigDecimal actualClosePrice;
    private BigDecimal actualHighPrice;
    private BigDecimal actualLowPrice;
    private Boolean targetHit;
    private Boolean stopLossHit;
    private BigDecimal actualPnlPct;
    private String predictionReason;
    private String aiTool;
    private String aiModel;
}
