package com.sensex.optiontrader.model.dto.response;

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
public class PredictionResponse {
    private LocalDate predictionDate;
    private String horizon;
    private Direction direction;
    private BigDecimal magnitude;
    private BigDecimal confidence;
    private BigDecimal predictedVolatility;
    private BigDecimal currentSensex;
    private BigDecimal targetSensex;
}