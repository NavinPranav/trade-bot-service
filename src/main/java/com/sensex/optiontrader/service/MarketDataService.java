package com.sensex.optiontrader.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sensex.optiontrader.integration.MarketDataProvider;
import com.sensex.optiontrader.integration.angelone.AngelOneHistoricalClient;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MarketDataService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final MarketDataProvider marketData;
    private final AngelOneHistoricalClient historicalClient;
    private final InstrumentRegistry instrumentRegistry;
    private final ObjectMapper objectMapper;

    /** OHLCV for the authenticated user's preferred underlying (cache key includes user + instrument). */
    @Cacheable(value = "marketData", key = "'v6-u'+#userId+'-'+#period+'-'+#interval", unless = "#result.isEmpty()")
    public List<Map<String, Object>> getOhlcvForUser(Long userId, String period, String interval) {
        var end = LocalDateTime.now(IST);
        var start = switch (period) {
            case "1D" -> end.minusDays(1);
            case "3D" -> end.minusDays(3);
            case "5D" -> end.minusDays(5);
            case "7D" -> end.minusDays(7);
            case "1M" -> end.minusMonths(1);
            case "3M" -> end.minusMonths(3);
            case "6M" -> end.minusMonths(6);
            case "5Y" -> end.minusYears(5);
            default   -> end.minusYears(1);
        };
        var inst = instrumentRegistry.getPrimaryForUser(userId).orElse(null);
        if (inst == null) {
            return List.of();
        }
        return historicalClient.getOhlcv(inst, start, end, interval);
    }

    @Cacheable(value = "marketData", key = "'v4-vix'", unless = "!#result.containsKey('timestamp')")
    public Map<String, Object> getCurrentVix() {
        return marketData.getVolatilityIndexSnapshot();
    }

    /**
     * India VIX points for ML ({@code repeated VixPoint}). Currently one row per {@link #getCurrentVix()};
     * extend when a time-series VIX feed is available.
     */
    public List<Map<String, Object>> getIndiaVixHistory() {
        Map<String, Object> snap = coerceToStringKeyMap(getCurrentVix());
        if (snap == null || snap.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> list = new ArrayList<>(1);
        list.add(snap);
        return list;
    }

    /**
     * Redis/Jackson can occasionally surface a JSON string instead of a {@code Map} for cached entries.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> coerceToStringKeyMap(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        if (raw instanceof String s) {
            try {
                return objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** Not yet available from Angel One streaming; reserved for a future NSE/BSE feed. */
    public List<Map<String, Object>> getFiiDiiActivity(int days) {
        return Collections.emptyList();
    }
}
