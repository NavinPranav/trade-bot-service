package com.sensex.optiontrader.model.dto.request;
import com.sensex.optiontrader.model.enums.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
@Data public class PositionRequest { @NotNull private StrategyType strategyType; @NotNull private OptionType optionType; @NotNull private BigDecimal strikePrice; @NotNull private LocalDate expiry; @NotNull @Positive private Integer lots; @NotNull private BigDecimal entryPrice; }