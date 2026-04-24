package com.sensex.optiontrader.integration.angelone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sensex.optiontrader.config.AngelOneProperties.InstrumentToken;
import com.sensex.optiontrader.service.InstrumentRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches historical OHLCV candle data from Angel One SmartAPI REST endpoint.
 */
@Slf4j
@Component
public class AngelOneHistoricalClient {

    private static final DateTimeFormatter ANGEL_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter ISO_TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final InstrumentRegistry instrumentRegistry;
    private final AngelOneAuthService authService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AngelOneHistoricalClient(InstrumentRegistry instrumentRegistry,
                                    AngelOneAuthService authService,
                                    RestClient angelOneRestClient,
                                    ObjectMapper objectMapper) {
        this.instrumentRegistry = instrumentRegistry;
        this.authService = authService;
        this.restClient = angelOneRestClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches OHLCV candle data for the given instrument (e.g. user's preferred underlying).
     *
     * @param start    start datetime
     * @param end      end datetime
     * @param interval bar interval: "1M", "5M", "15M", "30M", "1H", "1D"
     * @return list of OHLCV maps with keys: timestamp, open, high, low, close, volume
     */
    public List<Map<String, Object>> getOhlcv(InstrumentToken inst, LocalDateTime start, LocalDateTime end, String interval) {
        if (inst == null) return List.of();
        return fetchCandles(inst, start, end, toAngelInterval(interval));
    }

    /**
     * Fetches India VIX OHLCV as a volatility snapshot.
     * Uses the instrument named "INDIA VIX" if configured, otherwise returns a zero snapshot.
     */
    public Map<String, Object> getVixSnapshot() {
        InstrumentToken vixInst = findInstrument("INDIA VIX");
        if (vixInst == null) {
            return Map.of("vix", BigDecimal.ZERO);
        }

        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusDays(5);
        List<Map<String, Object>> candles = fetchCandles(vixInst, start, end, "ONE_DAY");

        if (candles.isEmpty()) {
            return Map.of("vix", BigDecimal.ZERO);
        }

        Map<String, Object> last = candles.get(candles.size() - 1);
        BigDecimal vix = toBd(last.get("close"));
        return Map.of(
                "vix", vix,
                "timestamp", last.getOrDefault("timestamp", end.format(ISO_TS))
        );
    }

    private List<Map<String, Object>> fetchCandles(InstrumentToken inst,
                                                    LocalDateTime start,
                                                    LocalDateTime end,
                                                    String angelInterval) {
        String jwt = authService.getJwtToken();
        if (jwt == null) {
            log.warn("Not authenticated — cannot fetch historical data");
            return List.of();
        }

        String exchange = resolveExchange(inst.exchange());
        Map<String, String> body = Map.of(
                "exchange", exchange,
                "symboltoken", inst.token(),
                "interval", angelInterval,
                "fromdate", start.format(ANGEL_TS),
                "todate", end.format(ANGEL_TS)
        );

        log.debug("Historical candle request: {} (JWT present: {})", body, jwt.length() > 10);

        try {
            String raw = restClient.post()
                    .uri("/rest/secure/angelbroking/historical/v1/getCandleData")
                    .header("Authorization", "Bearer " + jwt)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(raw);
            if (!root.path("status").asBoolean(false)) {
                log.warn("Angel One historical API error: status=false response={}", raw);
                return List.of();
            }

            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                log.warn("Angel One historical API returned no candle data: {}", raw);
                return List.of();
            }

            List<Map<String, Object>> rows = new ArrayList<>(data.size());
            for (JsonNode candle : data) {
                if (!candle.isArray() || candle.size() < 6) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                String ts = candle.get(0).asText();
                row.put("timestamp", normalizeTimestamp(ts));
                row.put("open", toBd(candle.get(1).asDouble()));
                row.put("high", toBd(candle.get(2).asDouble()));
                row.put("low", toBd(candle.get(3).asDouble()));
                row.put("close", toBd(candle.get(4).asDouble()));
                row.put("volume", candle.get(5).asLong());
                rows.add(row);
            }
            return rows;
        } catch (Exception e) {
            log.warn("Angel One historical fetch failed: {}", e.getMessage());
            return List.of();
        }
    }

    private InstrumentToken findInstrument(String name) {
        return instrumentRegistry.findByName(name).orElse(null);
    }

    private static String resolveExchange(String exchange) {
        if (exchange == null) return "NSE";
        return switch (exchange.toLowerCase()) {
            case "nse_cm", "nse" -> "NSE";
            case "bse_cm", "bse" -> "BSE";
            case "nse_fo", "nfo" -> "NFO";
            case "bse_fo", "bfo" -> "BFO";
            case "mcx", "mcx_fo" -> "MCX";
            case "cds", "cde", "ncx" -> "CDS";
            default -> exchange.toUpperCase();
        };
    }

    private static String toAngelInterval(String interval) {
        if (interval == null || interval.isBlank()) return "ONE_DAY";
        return switch (interval.trim().toUpperCase()) {
            case "1M" -> "ONE_MINUTE";
            case "5M" -> "FIVE_MINUTE";
            case "15M" -> "FIFTEEN_MINUTE";
            case "30M" -> "THIRTY_MINUTE";
            case "60M", "1H" -> "ONE_HOUR";
            default -> "ONE_DAY";
        };
    }

    private static String normalizeTimestamp(String ts) {
        try {
            if (ts.contains("T") && ts.contains("+")) {
                return LocalDateTime.parse(ts, DateTimeFormatter.ISO_OFFSET_DATE_TIME).format(ISO_TS);
            }
            return LocalDateTime.parse(ts).format(ISO_TS);
        } catch (Exception e) {
            return ts;
        }
    }

    private static BigDecimal toBd(Object v) {
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue()).setScale(4, RoundingMode.HALF_UP);
        try {
            return new BigDecimal(String.valueOf(v).trim()).setScale(4, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal toBd(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }
}
