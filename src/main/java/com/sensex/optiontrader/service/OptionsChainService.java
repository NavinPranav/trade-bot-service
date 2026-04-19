package com.sensex.optiontrader.service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Map;
@Service @RequiredArgsConstructor public class OptionsChainService {
    public Map<String,Object> getOptionsChain(String expiry) { return Map.of("expiry",expiry,"message","Options chain integration pending"); }
}