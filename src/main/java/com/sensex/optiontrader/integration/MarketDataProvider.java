package com.sensex.optiontrader.integration;

import com.sensex.optiontrader.integration.angelone.LiveTickData;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Pluggable market-data abstraction backed by Angel One SmartAPI.
 * Provides both historical OHLCV and real-time streaming tick data.
 */
public interface MarketDataProvider {

    String id();

    List<Map<String, Object>> getOhlcv(LocalDateTime start, LocalDateTime end, String interval);

    Map<String, Object> getVolatilityIndexSnapshot();

    /** Returns the most recent live tick for the given instrument token, or null if unavailable. */
    LiveTickData getLatestTick(String instrumentToken);

    /** Returns all currently cached live ticks keyed by instrument token. */
    Map<String, LiveTickData> getAllLatestTicks();
}
