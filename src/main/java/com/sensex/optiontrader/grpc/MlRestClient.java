package com.sensex.optiontrader.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sensex.optiontrader.config.AppProperties;
import com.sensex.optiontrader.exception.MlServiceUnavailableException;
import com.sensex.optiontrader.integration.angelone.LiveTickData;
import com.sensex.optiontrader.model.dto.response.PredictionResponse;
import com.sensex.optiontrader.model.enums.Direction;
import com.sensex.optiontrader.service.InstrumentRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * HTTP REST fallback for the ML service when gRPC is unreachable
 * (e.g. on Render free tier where only one port is exposed).
 */
@Slf4j
@Component
public class MlRestClient {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final RestClient restClient;
    private final AppProperties props;
    private final InstrumentRegistry instrumentRegistry;
    private final ObjectMapper objectMapper;

    public MlRestClient(AppProperties props,
                        InstrumentRegistry instrumentRegistry,
                        ObjectMapper objectMapper) {
        this.props = props;
        this.instrumentRegistry = instrumentRegistry;
        this.objectMapper = objectMapper;

        String httpUrl = props.getMlService().getHttpUrl();
        this.restClient = (httpUrl != null && !httpUrl.isBlank())
                ? RestClient.builder().baseUrl(httpUrl.replaceAll("/+$", "")).build()
                : null;
    }

    public boolean isConfigured() {
        return restClient != null;
    }

    public PredictionResponse predict(
            String engine,
            String horizon,
            List<Map<String, Object>> sensexOhlcv,
            List<Map<String, Object>> indiaVixHistory,
            LiveTickData liveTick,
            Long userId) {

        if (restClient == null) {
            throw new MlServiceUnavailableException(
                    "ML HTTP URL not configured (set ML_SERVICE_HTTP_URL)", null);
        }

        var primary = instrumentRegistry.getPrimaryForUser(userId);
        String underlying = primary
                .map(com.sensex.optiontrader.config.AngelOneProperties.InstrumentToken::name)
                .orElse("");
        String instrumentToken = primary
                .map(com.sensex.optiontrader.config.AngelOneProperties.InstrumentToken::token)
                .orElse("");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("horizon", horizon != null ? horizon : "1D");
        body.put("sensex_ohlcv", toOhlcvBarList(sensexOhlcv));
        body.put("india_vix", toVixPointList(indiaVixHistory));
        body.put("sensex_quote", buildQuote(sensexOhlcv, liveTick));
        body.put("underlying_symbol", underlying);
        body.put("instrument_token", instrumentToken);
        body.put("engine", engine != null ? engine : "AI");

        try {
            String reqJson = objectMapper.writeValueAsString(body);
            log.info("ML REST /predict: engine={} horizon={} bars={} underlying={}",
                    engine, horizon,
                    sensexOhlcv != null ? sensexOhlcv.size() : 0,
                    underlying);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/predict")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(reqJson)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                throw new MlServiceUnavailableException("ML REST returned null response", null);
            }

            return fromRestResponse(response);
        } catch (MlServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.error("ML REST /predict failed: {}", e.getMessage());
            throw new MlServiceUnavailableException("ML REST call failed: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> toOhlcvBarList(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        List<Map<String, Object>> bars = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            long unixMs = timestampToUnixMs(row.get("timestamp"));
            if (unixMs <= 0) continue;
            double o = toDouble(row.get("open"));
            double h = toDouble(row.get("high"));
            double l = toDouble(row.get("low"));
            double c = toDouble(row.get("close"));
            long vol = toLong(row.get("volume"));
            if (o == 0 && h == 0 && l == 0 && c == 0) continue;
            bars.add(Map.of(
                    "timestamp_unix_ms", unixMs,
                    "open", o,
                    "high", h,
                    "low", l,
                    "close", c,
                    "volume", vol
            ));
        }
        return bars;
    }

    private List<Map<String, Object>> toVixPointList(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        List<Map<String, Object>> points = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            double vv = toDouble(row.get("vix"));
            if (!Double.isFinite(vv) || vv <= 0) continue;
            long unixMs = System.currentTimeMillis();
            Object ts = row.get("timestamp");
            if (ts != null) {
                long parsed = timestampToUnixMs(ts);
                if (parsed > 0) unixMs = parsed;
            }
            points.add(Map.of("timestamp_unix_ms", unixMs, "vix", vv));
        }
        return points;
    }

    private Map<String, Object> buildQuote(List<Map<String, Object>> ohlcvRows, LiveTickData liveTick) {
        if (liveTick != null && liveTick.getLastTradedPrice() > 0) {
            return Map.of(
                    "price", liveTick.getLastTradedPrice(),
                    "change", liveTick.change(),
                    "change_pct", liveTick.changePct()
            );
        }
        if (ohlcvRows != null && !ohlcvRows.isEmpty()) {
            Map<String, Object> last = ohlcvRows.get(ohlcvRows.size() - 1);
            double close = toDouble(last.get("close"));
            if (close > 0) {
                return Map.of("price", close, "change", 0.0, "change_pct", 0.0);
            }
        }
        return Map.of("price", 0.0, "change", 0.0, "change_pct", 0.0);
    }

    private static PredictionResponse fromRestResponse(Map<String, Object> r) {
        LocalDate date;
        try {
            Object pd = r.get("prediction_date");
            date = (pd != null && !pd.toString().isBlank())
                    ? LocalDate.parse(pd.toString()) : LocalDate.now();
        } catch (Exception e) {
            date = LocalDate.now();
        }
        Object qn = r.get("ai_quota_notice");
        String quota = qn != null && !qn.toString().isBlank() ? qn.toString().trim() : null;
        Object pr = r.get("prediction_reason");
        String reason = pr != null && !pr.toString().isBlank() ? pr.toString().trim() : null;
        return PredictionResponse.builder()
                .predictionDate(date)
                .predictionTimestampMs(System.currentTimeMillis())
                .horizon(String.valueOf(r.getOrDefault("horizon", "")))
                .direction(parseDirection(String.valueOf(r.getOrDefault("direction", "NEUTRAL"))))
                .magnitude(toBigDecimal(r.get("magnitude")))
                .confidence(toBigDecimal(r.get("confidence")))
                .predictedVolatility(toBigDecimal(r.get("predicted_volatility")))
                .currentSensex(toBigDecimal(r.get("current_sensex")))
                .targetSensex(toBigDecimal(r.get("target_sensex")))
                .entryPrice(toBigDecimal(r.get("entry_price")))
                .stopLoss(toBigDecimal(r.get("stop_loss")))
                .targetPrice(toBigDecimal(r.get("target_price")))
                .riskReward(toBigDecimal(r.get("risk_reward")))
                .validMinutes(toInt(r.get("valid_minutes")))
                .noTradeZone(toDouble(r.get("confidence")) < 65.0)
                .aiQuotaNotice(quota)
                .predictionReason(reason)
                .build();
    }

    private static Direction parseDirection(String s) {
        if (s == null || s.isBlank()) return Direction.NEUTRAL;
        try {
            return Direction.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Direction.NEUTRAL;
        }
    }

    private static BigDecimal toBigDecimal(Object x) {
        if (x == null) return BigDecimal.ZERO;
        if (x instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(x.toString().trim()); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static long timestampToUnixMs(Object ts) {
        if (ts == null) return 0L;
        String s = String.valueOf(ts).trim();
        if (s.isEmpty()) return 0L;
        try {
            LocalDateTime ldt = LocalDateTime.parse(s, ISO_LOCAL);
            return ldt.atZone(IST).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            try { return Instant.parse(s).toEpochMilli(); }
            catch (Exception e2) { return 0L; }
        }
    }

    private static double toDouble(Object x) {
        if (x == null) return 0.0;
        if (x instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(x).trim()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private static long toLong(Object x) {
        if (x == null) return 0L;
        if (x instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(x).trim()); }
        catch (NumberFormatException e) { return 0L; }
    }

    private static Integer toInt(Object x) {
        if (x == null) return null;
        if (x instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(x).trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
