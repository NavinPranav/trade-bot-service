package com.sensex.optiontrader.grpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sensex.optiontrader.config.AppProperties;
import com.sensex.optiontrader.service.InstrumentRegistry;
import com.sensex.optiontrader.grpc.proto.BacktestProgress;
import com.sensex.optiontrader.grpc.proto.BacktestRequest;
import com.sensex.optiontrader.grpc.proto.Empty;
import com.sensex.optiontrader.grpc.proto.FeatureImportanceResponse;
import com.sensex.optiontrader.grpc.proto.LiveTick;
import com.sensex.optiontrader.grpc.proto.OhlcvBar;
import com.sensex.optiontrader.grpc.proto.PredictionRequest;
import com.sensex.optiontrader.grpc.proto.PredictionServiceGrpc;
import com.sensex.optiontrader.grpc.proto.SensexQuote;
import com.sensex.optiontrader.grpc.proto.StreamAck;
import com.sensex.optiontrader.grpc.proto.VixPoint;
import com.sensex.optiontrader.model.dto.response.PredictionResponse;
import com.sensex.optiontrader.model.entity.BacktestJob;
import com.sensex.optiontrader.model.enums.Direction;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class MlServiceClient {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ManagedChannel mlServiceChannel;
    private final AppProperties props;
    private final InstrumentRegistry instrumentRegistry;
    private final ObjectMapper objectMapper;
    private final GrpcErrorHandler grpcErrorHandler;
    private final AtomicReference<StreamObserver<LiveTick>> liveTickStream = new AtomicReference<>();

    public MlServiceClient(ManagedChannel mlServiceChannel,
                           AppProperties props,
                           InstrumentRegistry instrumentRegistry,
                           ObjectMapper objectMapper,
                           GrpcErrorHandler grpcErrorHandler) {
        this.mlServiceChannel = mlServiceChannel;
        this.props = props;
        this.instrumentRegistry = instrumentRegistry;
        this.objectMapper = objectMapper;
        this.grpcErrorHandler = grpcErrorHandler;
    }

    /**
     * Classical ML ensemble prediction (prediction_engine=ML).
     */
    public PredictionResponse getPrediction(
            String horizon,
            List<Map<String, Object>> sensexOhlcv,
            List<Map<String, Object>> indiaVixHistory,
            com.sensex.optiontrader.integration.angelone.LiveTickData liveTick) {
        return callPrediction("ML", horizon, sensexOhlcv, indiaVixHistory, liveTick);
    }

    /**
     * Google Gemini AI prediction (prediction_engine=AI).
     */
    public PredictionResponse getGeminiPrediction(
            String horizon,
            List<Map<String, Object>> sensexOhlcv,
            List<Map<String, Object>> indiaVixHistory,
            com.sensex.optiontrader.integration.angelone.LiveTickData liveTick) {
        return callPrediction("AI", horizon, sensexOhlcv, indiaVixHistory, liveTick);
    }

    private PredictionResponse callPrediction(
            String engine,
            String horizon,
            List<Map<String, Object>> sensexOhlcv,
            List<Map<String, Object>> indiaVixHistory,
            com.sensex.optiontrader.integration.angelone.LiveTickData liveTick) {
        sensexOhlcv = normalizeListOfMaps(sensexOhlcv, "sensex_ohlcv");
        indiaVixHistory = normalizeListOfMaps(indiaVixHistory, "india_vix");

        var stub = PredictionServiceGrpc.newBlockingStub(mlServiceChannel)
                .withDeadlineAfter(props.getMlService().getTimeoutMs(), TimeUnit.MILLISECONDS);

        String underlying = primaryInstrumentName();
        var req = PredictionRequest.newBuilder().setHorizon(horizon == null ? "" : horizon).setUnderlyingSymbol(underlying);
        for (Map<String, Object> row : sensexOhlcv) {
            OhlcvBar bar = toOhlcvBar(row);
            if (bar != null) {
                req.addSensexOhlcv(bar);
            }
        }
        if (indiaVixHistory != null) {
            for (Map<String, Object> row : indiaVixHistory) {
                VixPoint vix = toVixPoint(row);
                if (vix != null) {
                    req.addIndiaVix(vix);
                }
            }
        }
        req.setSensexQuote(buildQuote(sensexOhlcv, liveTick));

        boolean useGemini = "AI".equalsIgnoreCase(engine);
        String rpcName = useGemini ? "GetGeminiPrediction" : "GetPrediction";

        try {
            var built = req.build();
            if (log.isDebugEnabled()) {
                log.debug(
                        "ML {}: horizon={} sensexBars={} indiaVixPoints={} underlying={}",
                        rpcName, horizon,
                        built.getSensexOhlcvCount(),
                        built.getIndiaVixCount(),
                        built.getUnderlyingSymbol());
            }
            var proto = useGemini ? stub.getGeminiPrediction(built) : stub.getPrediction(built);
            return fromProto(proto);
        } catch (StatusRuntimeException e) {
            log.warn("{} failed: {} — check ML server logs", rpcName, e.getStatus());
            throw grpcErrorHandler.translate(e);
        }
    }

    public FeatureImportanceResponse getFeatureImportance() {
        var stub = PredictionServiceGrpc.newBlockingStub(mlServiceChannel)
                .withDeadlineAfter(props.getMlService().getTimeoutMs(), TimeUnit.MILLISECONDS);
        try {
            return stub.getFeatureImportance(Empty.getDefaultInstance());
        } catch (StatusRuntimeException e) {
            throw grpcErrorHandler.translate(e);
        }
    }

    public Map<String, Object> runBacktest(BacktestJob job) {
        var stub = PredictionServiceGrpc.newBlockingStub(mlServiceChannel)
                .withDeadlineAfter(props.getMlService().getBacktestTimeoutMs(), TimeUnit.MILLISECONDS);
        var req = BacktestRequest.newBuilder()
                .setStrategyType(job.getStrategyType() != null ? job.getStrategyType().name() : "")
                .setStartDate(job.getStartDate() != null ? job.getStartDate().toString() : "")
                .setEndDate(job.getEndDate() != null ? job.getEndDate().toString() : "")
                .setParametersJson(parametersJson(job))
                .build();
        try {
            Iterator<BacktestProgress> it = stub.runBacktest(req);
            BacktestProgress last = null;
            while (it.hasNext()) {
                last = it.next();
            }
            if (last != null && !last.getResultJson().isBlank()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = objectMapper.readValue(last.getResultJson(), Map.class);
                    return parsed;
                } catch (JsonProcessingException e) {
                    log.warn("Backtest result JSON parse failed: {}", e.getMessage());
                    return Map.of("raw", last.getResultJson());
                }
            }
            return Map.of();
        } catch (StatusRuntimeException e) {
            throw grpcErrorHandler.translate(e);
        }
    }

    public com.sensex.optiontrader.grpc.proto.ModelHealthResponse getModelHealth() {
        var stub = PredictionServiceGrpc.newBlockingStub(mlServiceChannel)
                .withDeadlineAfter(props.getMlService().getTimeoutMs(), TimeUnit.MILLISECONDS);
        try {
            return stub.getModelHealth(Empty.getDefaultInstance());
        } catch (StatusRuntimeException e) {
            throw grpcErrorHandler.translate(e);
        }
    }

    /**
     * Redis cache can deserialize {@code List<Map>} as a list of JSON strings or mixed types depending on
     * serializer history; normalize before iterating so we never cast {@link String} to {@link Map}.
     */
    private List<Map<String, Object>> normalizeListOfMaps(List<?> raw, String label) {
        if (raw == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) m;
                out.add(row);
            } else if (o instanceof String s) {
                try {
                    out.add(objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {}));
                } catch (Exception e) {
                    log.warn("{}: skip row (expected JSON object): {}", label, e.getMessage());
                }
            } else {
                log.warn("{}: skip row of type {}", label, o != null ? o.getClass().getName() : "null");
            }
        }
        return out;
    }

    private String parametersJson(BacktestJob job) {
        if (job.getParameters() == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(job.getParameters());
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static VixPoint toVixPoint(Map<String, Object> vix) {
        if (vix == null || vix.isEmpty()) {
            return null;
        }
        Object raw = vix.get("vix");
        double vv = toDouble(raw);
        if (!Double.isFinite(vv) || vv <= 0) {
            return null;
        }
        long unixMs = System.currentTimeMillis();
        Object ts = vix.get("timestamp");
        if (ts != null) {
            long parsed = timestampToUnixMs(ts);
            if (parsed > 0) {
                unixMs = parsed;
            }
        }
        return VixPoint.newBuilder().setTimestampUnixMs(unixMs).setVix(finitePrice(vv)).build();
    }

    /**
     * Builds a SensexQuote preferring a live tick (real-time price/change) when available,
     * falling back to the last historical OHLCV bar's close otherwise.
     */
    private static SensexQuote buildQuote(
            List<Map<String, Object>> ohlcvRows,
            com.sensex.optiontrader.integration.angelone.LiveTickData liveTick) {
        if (liveTick != null && liveTick.getLastTradedPrice() > 0) {
            return SensexQuote.newBuilder()
                    .setPrice(liveTick.getLastTradedPrice())
                    .setChange(liveTick.change())
                    .setChangePct(liveTick.changePct())
                    .build();
        }
        if (ohlcvRows == null || ohlcvRows.isEmpty()) {
            return SensexQuote.getDefaultInstance();
        }
        Map<String, Object> last = ohlcvRows.get(ohlcvRows.size() - 1);
        double close = finitePrice(toDouble(last.get("close")));
        if (close <= 0) {
            return SensexQuote.getDefaultInstance();
        }
        return SensexQuote.newBuilder().setPrice(close).setChange(0).setChangePct(0).build();
    }

    private static OhlcvBar toOhlcvBar(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        Object ts = row.get("timestamp");
        if (ts == null) {
            return null;
        }
        long unixMs = timestampToUnixMs(ts);
        if (unixMs <= 0L) {
            return null;
        }
        double o = toDouble(row.get("open"));
        double h = toDouble(row.get("high"));
        double l = toDouble(row.get("low"));
        double c = toDouble(row.get("close"));
        long vol = 0L;
        Object v = row.get("volume");
        if (v != null) {
            if (v instanceof Number n) {
                vol = n.longValue();
            } else {
                try {
                    vol = Long.parseLong(String.valueOf(v).trim());
                } catch (NumberFormatException ignored) {
                    vol = 0L;
                }
            }
        }
        o = finitePrice(o);
        h = finitePrice(h);
        l = finitePrice(l);
        c = finitePrice(c);
        if (o == 0 && h == 0 && l == 0 && c == 0) {
            return null;
        }
        return OhlcvBar.newBuilder()
                .setTimestampUnixMs(unixMs)
                .setOpen(o)
                .setHigh(h)
                .setLow(l)
                .setClose(c)
                .setVolume(vol)
                .build();
    }

    private static long timestampToUnixMs(Object ts) {
        String s = String.valueOf(ts).trim();
        if (s.isEmpty()) {
            return 0L;
        }
        try {
            LocalDateTime ldt = LocalDateTime.parse(s, ISO_LOCAL);
            return ldt.atZone(IST).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            try {
                return Instant.parse(s).toEpochMilli();
            } catch (Exception e2) {
                return 0L;
            }
        }
    }

    private static double finitePrice(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }

    private static double toDouble(Object x) {
        if (x == null) {
            return 0.0;
        }
        if (x instanceof BigDecimal b) {
            return b.doubleValue();
        }
        if (x instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(x).trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Sends a single live tick to the ML service over a persistent client-streaming gRPC call.
     * The stream is lazily opened on first invocation and reused for subsequent ticks.
     */
    public void sendLiveTick(String symbol, int exchangeType, String token,
                             double ltp, double open, double high, double low, double close,
                             double change, double changePct, long volume, long timestampMs) {
        StreamObserver<LiveTick> stream = getOrCreateTickStream();
        if (stream == null) return;

        LiveTick tick = LiveTick.newBuilder()
                .setSymbol(symbol)
                .setExchangeType(exchangeType)
                .setToken(token)
                .setLastTradedPrice(ltp)
                .setOpen(open)
                .setHigh(high)
                .setLow(low)
                .setClose(close)
                .setChange(change)
                .setChangePct(changePct)
                .setVolume(volume)
                .setTimestampUnixMs(timestampMs)
                .build();
        try {
            stream.onNext(tick);
        } catch (Exception e) {
            log.debug("Live tick stream error, will re-open: {}", e.getMessage());
            liveTickStream.set(null);
        }
    }

    private StreamObserver<LiveTick> getOrCreateTickStream() {
        StreamObserver<LiveTick> existing = liveTickStream.get();
        if (existing != null) return existing;

        try {
            var stub = PredictionServiceGrpc.newStub(mlServiceChannel);
            StreamObserver<LiveTick> stream = stub.streamLiveTicks(new StreamObserver<StreamAck>() {
                @Override public void onNext(StreamAck ack) {
                    log.debug("ML StreamAck: accepted={} msg={}", ack.getAccepted(), ack.getMessage());
                }
                @Override public void onError(Throwable t) {
                    log.warn("ML live tick stream error: {}", t.getMessage());
                    liveTickStream.set(null);
                }
                @Override public void onCompleted() {
                    log.info("ML live tick stream completed");
                    liveTickStream.set(null);
                }
            });
            liveTickStream.set(stream);
            return stream;
        } catch (Exception e) {
            log.warn("Failed to open live tick stream: {}", e.getMessage());
            return null;
        }
    }

    private String primaryInstrumentName() {
        return instrumentRegistry.getPrimary()
                .map(com.sensex.optiontrader.config.AngelOneProperties.InstrumentToken::name)
                .orElse("");
    }

    private static PredictionResponse fromProto(com.sensex.optiontrader.grpc.proto.PredictionResponse p) {
        LocalDate date;
        try {
            date = p.getPredictionDate().isBlank() ? LocalDate.now() : LocalDate.parse(p.getPredictionDate());
        } catch (Exception e) {
            date = LocalDate.now();
        }
        return PredictionResponse.builder()
                .predictionDate(date)
                .horizon(p.getHorizon())
                .direction(parseDirection(p.getDirection()))
                .magnitude(BigDecimal.valueOf(p.getMagnitude()))
                .confidence(BigDecimal.valueOf(p.getConfidence()))
                .predictedVolatility(BigDecimal.valueOf(p.getPredictedVolatility()))
                .currentSensex(p.getCurrentSensex() != 0 ? BigDecimal.valueOf(p.getCurrentSensex()) : null)
                .targetSensex(p.getTargetSensex() != 0 ? BigDecimal.valueOf(p.getTargetSensex()) : null)
                .build();
    }

    private static Direction parseDirection(String s) {
        if (s == null || s.isBlank()) {
            return Direction.NEUTRAL;
        }
        try {
            return Direction.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Direction.NEUTRAL;
        }
    }
}
