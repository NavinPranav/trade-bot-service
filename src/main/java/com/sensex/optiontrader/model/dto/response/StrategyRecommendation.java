package com.sensex.optiontrader.model.dto.response;
import com.sensex.optiontrader.model.enums.StrategyType;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;
@Data @Builder @AllArgsConstructor public class StrategyRecommendation {
    private StrategyType strategyType; private String rationale; private BigDecimal maxProfit; private BigDecimal maxLoss; private BigDecimal breakeven; private BigDecimal riskRewardRatio; private List<LegDetail> legs;
    @Data @Builder @AllArgsConstructor public static class LegDetail { private String action; private String optionType; private BigDecimal strike; private String expiry; private BigDecimal premium; private Integer lots; }
}