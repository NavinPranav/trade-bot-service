package com.sensex.optiontrader.integration.angelone;

import com.sensex.optiontrader.integration.MarketDataProvider;
import com.sensex.optiontrader.service.InstrumentRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class AngelOneMarketDataProvider implements MarketDataProvider {

    private final AngelOneHistoricalClient historicalClient;
    private final InstrumentRegistry instrumentRegistry;

    /**
     * Most recent live tick per instrument token, updated by the streaming service.
     * Keyed by Angel One symbol token (e.g. "99919000" for SENSEX).
     */
    private final ConcurrentHashMap<String, LiveTickData> latestTicks = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "angel_one";
    }

    @Override
    public List<Map<String, Object>> getOhlcv(LocalDateTime start, LocalDateTime end, String interval) {
        return historicalClient.getOhlcv(start, end, interval);
    }

    @Override
    public Map<String, Object> getVolatilityIndexSnapshot() {
        LiveTickData vixTick = findLiveVixTick();
        if (vixTick != null && vixTick.getLastTradedPrice() > 0) {
            BigDecimal vix = BigDecimal.valueOf(vixTick.getLastTradedPrice())
                    .setScale(4, RoundingMode.HALF_UP);
            String ts = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(vixTick.getExchangeTimestampMs()),
                    ZoneId.of("Asia/Kolkata")
            ).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return Map.of("vix", vix, "timestamp", ts);
        }
        return historicalClient.getVixSnapshot();
    }

    private LiveTickData findLiveVixTick() {
        return instrumentRegistry.findByName("INDIA VIX")
                .map(i -> latestTicks.get(i.token()))
                .orElse(null);
    }

    @Override
    public LiveTickData getLatestTick(String instrumentToken) {
        return latestTicks.get(instrumentToken);
    }

    @Override
    public Map<String, LiveTickData> getAllLatestTicks() {
        return Map.copyOf(latestTicks);
    }

    public void updateTick(LiveTickData tick) {
        latestTicks.put(tick.getToken(), tick);
    }

    public String resolveInstrumentName(String token) {
        return instrumentRegistry.resolveInstrumentName(token);
    }
}
