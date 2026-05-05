package com.sensex.optiontrader.model.dto.response;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class UserRiskSettingsResponse {
    boolean tradingHalted;
    Integer maxSignalsPerDay;
    BigDecimal maxDailyLossPct;
    long directionalSignalsToday;
    BigDecimal resolvedPnlSumTodayPct;
}
