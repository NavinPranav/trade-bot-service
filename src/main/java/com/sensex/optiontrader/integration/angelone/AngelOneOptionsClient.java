package com.sensex.optiontrader.integration.angelone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches the nearest-expiry options chain from Angel One SmartAPI.
 *
 * <p>Two API calls are made per snapshot — one for CE, one for PE — then merged
 * by strike price. Each merged row contains both call and put OI, volume, IV, and LTP.
 *
 * <p>Angel One option chain endpoint:
 * {@code POST /rest/secure/angelbroking/market/v1/optionChain}
 * Body: {@code {"name":"BANKNIFTY","expirydate":"16JAN2025","optiontype":"CE","strikecount":"10"}}
 */
@Slf4j
@Component
public class AngelOneOptionsClient {

    private static final DateTimeFormatter EXPIRY_FMT = DateTimeFormatter.ofPattern("ddMMMyyyy", Locale.ENGLISH);

    private final AngelOneAuthService authService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AngelOneOptionsClient(AngelOneAuthService authService,
                                  RestClient angelOneRestClient,
                                  ObjectMapper objectMapper) {
        this.authService = authService;
        this.restClient = angelOneRestClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the merged options chain for the nearest weekly expiry.
     * Each row map has keys: strike, call_oi, put_oi, call_volume, put_volume,
     * call_iv, put_iv, call_ltp, put_ltp.
     * Returns an empty list on any error (non-fatal — predictions degrade gracefully to N/A).
     */
    public List<Map<String, Object>> fetchNearestExpiry(String underlyingName) {
        String expiry = nearestBankNiftyExpiry();
        log.debug("Options chain fetch: underlying={} expiry={}", underlyingName, expiry);
        try {
            Map<String, Map<String, Object>> ceByStrike = fetchByType(underlyingName, expiry, "CE");
            Map<String, Map<String, Object>> peByStrike = fetchByType(underlyingName, expiry, "PE");
            return merge(ceByStrike, peByStrike);
        } catch (Exception e) {
            log.warn("Options chain fetch failed for {}: {}", underlyingName, e.getMessage());
            return List.of();
        }
    }

    private Map<String, Map<String, Object>> fetchByType(String name, String expiry, String optionType) {
        // Lazily authenticate so options chain works even before the first WebSocket/STOMP connection.
        authService.ensureAuthenticated();
        String jwt = authService.getJwtToken();
        if (jwt == null) {
            log.warn("Not authenticated — cannot fetch options chain");
            return Map.of();
        }

        Map<String, String> body = Map.of(
                "name", name,
                "expirydate", expiry,
                "optiontype", optionType,
                "strikecount", "10"
        );

        try {
            String raw = restClient.post()
                    .uri("/rest/secure/angelbroking/market/v1/optionChain")
                    .header("Authorization", "Bearer " + jwt)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            log.debug("Options chain raw response ({}) first 300 chars: {}", optionType,
                    raw != null ? raw.substring(0, Math.min(300, raw.length())) : "null");

            if (raw == null || raw.isBlank()) {
                log.warn("Options chain {} returned empty response", optionType);
                return Map.of();
            }
            if (!raw.stripLeading().startsWith("{") && !raw.stripLeading().startsWith("[")) {
                log.warn("Options chain {} returned non-JSON response (first 500 chars): {}",
                        optionType, raw.substring(0, Math.min(500, raw.length())));
                return Map.of();
            }

            JsonNode root = objectMapper.readTree(raw);
            if (!root.path("status").asBoolean(false)) {
                log.warn("Angel One options chain status=false ({}): {}", optionType, raw.substring(0, Math.min(200, raw.length())));
                return Map.of();
            }

            return parseStrikeMap(root.path("data"), optionType);
        } catch (Exception e) {
            log.warn("Options chain {} request failed: {}", optionType, e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Map<String, Object>> parseStrikeMap(JsonNode data, String optionType) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (!data.isArray()) return result;

        for (JsonNode item : data) {
            // Angel One returns {strikePrice, expiryDate, CE/PE: {...}}
            String strikeStr = item.path("strikePrice").asText("0");
            JsonNode opt = item.path(optionType);
            if (opt.isMissingNode()) {
                // Fallback: some versions nest differently
                opt = item;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("oi",     opt.path("openInterest").asLong(0));
            row.put("volume", opt.path("totalTradedVolume").asLong(0));
            row.put("iv",     opt.path("impliedVolatility").asDouble(0.0));
            row.put("ltp",    opt.path("lastPrice").asDouble(0.0));
            result.put(strikeStr, row);
        }
        return result;
    }

    private List<Map<String, Object>> merge(Map<String, Map<String, Object>> ce,
                                             Map<String, Map<String, Object>> pe) {
        // Union of all strikes from both sides
        var allStrikes = new java.util.TreeSet<String>();
        allStrikes.addAll(ce.keySet());
        allStrikes.addAll(pe.keySet());

        List<Map<String, Object>> result = new ArrayList<>(allStrikes.size());
        for (String strikeStr : allStrikes) {
            double strike;
            try { strike = Double.parseDouble(strikeStr); } catch (NumberFormatException e) { continue; }

            Map<String, Object> ceRow = ce.getOrDefault(strikeStr, Map.of());
            Map<String, Object> peRow = pe.getOrDefault(strikeStr, Map.of());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("strike",       strike);
            row.put("call_oi",      toLong(ceRow.get("oi")));
            row.put("put_oi",       toLong(peRow.get("oi")));
            row.put("call_volume",  toLong(ceRow.get("volume")));
            row.put("put_volume",   toLong(peRow.get("volume")));
            row.put("call_iv",      toDouble(ceRow.get("iv")));
            row.put("put_iv",       toDouble(peRow.get("iv")));
            row.put("call_ltp",     toDouble(ceRow.get("ltp")));
            row.put("put_ltp",      toDouble(peRow.get("ltp")));
            result.add(row);
        }
        return result;
    }

    /**
     * Returns the next BANKNIFTY weekly expiry (Thursday) in Angel One format: e.g. "16JAN2025".
     * If today is Thursday, uses today's expiry. Falls back to the next Thursday.
     */
    private static String nearestBankNiftyExpiry() {
        LocalDate today = LocalDate.now();
        LocalDate expiry = today;
        int daysUntilThursday = DayOfWeek.THURSDAY.getValue() - today.getDayOfWeek().getValue();
        if (daysUntilThursday < 0) {
            daysUntilThursday += 7;
        }
        if (daysUntilThursday > 0) {
            expiry = today.plusDays(daysUntilThursday);
        }
        return expiry.format(EXPIRY_FMT).toUpperCase();
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v).trim()); } catch (Exception e) { return 0L; }
    }

    private static double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v).trim()); } catch (Exception e) { return 0.0; }
    }
}
