package com.sensex.optiontrader.model.dto.request;
import com.sensex.optiontrader.model.enums.AlertType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
@Data public class AlertRequest { @NotNull private AlertType alertType; @NotNull private BigDecimal threshold; private String description; }