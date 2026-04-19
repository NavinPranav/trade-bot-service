package com.sensex.optiontrader.model.dto.request;
import com.sensex.optiontrader.model.enums.StrategyType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDate;
import java.util.Map;
@Data public class BacktestRequest { @NotNull private StrategyType strategyType; @NotNull private LocalDate startDate; @NotNull private LocalDate endDate; private Map<String,Object> parameters; }