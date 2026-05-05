package com.sensex.optiontrader.model.dto.request;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class UserRiskSettingsRequest {
    private Boolean tradingHalted;
    /** Null or omit = unlimited directional signals per IST day. */
    private Integer maxSignalsPerDay;
    /** Positive % e.g. 2.5 = block when sum of resolved P&amp;L today &lt;= -2.5. Null = unlimited. */
    private BigDecimal maxDailyLossPct;
}
