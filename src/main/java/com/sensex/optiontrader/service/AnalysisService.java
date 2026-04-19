package com.sensex.optiontrader.service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Map;
@Service @RequiredArgsConstructor public class AnalysisService {
    public Map<String,Object> calculateMaxPain(String expiry) { return Map.of("expiry",expiry,"message","Pending options data"); }
    public Map<String,Object> getPcr() { return Map.of("message","Pending options data"); }
    public Map<String,Object> getIvSkew(String expiry) { return Map.of("expiry",expiry,"message","Pending options data"); }
}