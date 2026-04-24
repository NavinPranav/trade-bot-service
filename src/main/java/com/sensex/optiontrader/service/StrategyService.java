package com.sensex.optiontrader.service;
import com.sensex.optiontrader.model.dto.response.StrategyRecommendation;
import com.sensex.optiontrader.model.enums.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

@Service @RequiredArgsConstructor
public class StrategyService {
    private final PredictionService predSvc;
    private final MarketDataService mktSvc;

    public StrategyRecommendation getRecommendation(Long userId) {
        var p = predSvc.getLatestPrediction("1D", userId);
        var vix = mktSvc.getCurrentVix();
        boolean highIv = vix.containsKey("vix") && new BigDecimal(vix.get("vix").toString()).compareTo(BigDecimal.valueOf(18))>0;
        boolean highConf = p.getConfidence().compareTo(BigDecimal.valueOf(65))>0;
        var st = selectStrategy(p.getDirection().toBias(), highIv, highConf);
        return StrategyRecommendation.builder().strategyType(st).rationale("Based on model prediction").legs(List.of()).build();
    }
    private StrategyType selectStrategy(Direction d, boolean hIv, boolean hC) {
        if(d==Direction.BULLISH&&hC) return hIv?StrategyType.BULL_CALL_SPREAD:StrategyType.LONG_CALL;
        if(d==Direction.BEARISH&&hC) return hIv?StrategyType.BEAR_PUT_SPREAD:StrategyType.LONG_PUT;
        if(d==Direction.BULLISH) return StrategyType.BULL_PUT_SPREAD;
        if(d==Direction.BEARISH) return StrategyType.BEAR_CALL_SPREAD;
        return hIv?StrategyType.SHORT_STRADDLE:StrategyType.LONG_STRADDLE;
    }
    public Map<String,Object> calculatePayoff(Map<String,Object> legs) { return Map.of("payoff",List.of()); }
    public List<Map<String,Object>> getTemplates() {
        return List.of(Map.of("name","Bull Call Spread","type",StrategyType.BULL_CALL_SPREAD), Map.of("name","Iron Condor","type",StrategyType.IRON_CONDOR));
    }
}