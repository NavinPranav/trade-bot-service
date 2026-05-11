package com.sensex.optiontrader.service;

import com.sensex.optiontrader.config.AppProperties;
import com.sensex.optiontrader.model.entity.AiParameterSetting;
import com.sensex.optiontrader.model.entity.User;
import com.sensex.optiontrader.repository.AiParameterSettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the {@code ai_parameter_settings} table (Phase 4.4) and keeps the ML
 * service in sync. Mirrors the same pattern used by {@code AiPromptService}
 * and {@code AiModelService}: Postgres is the source of truth, the ML service
 * receives a flattened {@code {key: enabled}} map on startup and after every
 * admin change so the runtime payload assembly can drop disabled sections.
 *
 * <p>Required parameters (e.g. raw OHLCV, indicators, trend context) cannot
 * be disabled. The service refuses such requests with an
 * {@link IllegalArgumentException} which the controller maps to a 400.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiParameterService {

    private final AiParameterSettingRepository repo;
    private final AppProperties props;

    /** Push current settings to the ML service on startup so it boots in the right state. */
    @PostConstruct
    public void syncOnStartup() {
        try {
            Map<String, Boolean> map = currentToggleMap();
            if (map.isEmpty()) {
                log.info("[PARAMS] No ai_parameter_settings rows yet — skipping ML sync");
                return;
            }
            pushToMlService(map);
            log.info("[PARAMS] Synced {} parameter toggle(s) to ML service on startup", map.size());
        } catch (Exception e) {
            log.warn("[PARAMS] Startup sync to ML service failed: {}", e.getMessage());
        }
    }

    public List<AiParameterSetting> listAll() {
        return repo.findAllByOrderBySortOrderAscIdAsc();
    }

    /** Convenience for callers that only need the enabled set (e.g. backend filtering). */
    public Map<String, Boolean> currentToggleMap() {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (AiParameterSetting s : repo.findAllByOrderBySortOrderAscIdAsc()) {
            map.put(s.getParameterKey(), s.isEnabled());
        }
        return map;
    }

    @Transactional
    public AiParameterSetting updateEnabled(String parameterKey, boolean enabled, User actor) {
        AiParameterSetting setting = repo.findByParameterKey(parameterKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown parameter key: " + parameterKey));

        if (setting.isRequired() && !enabled) {
            throw new IllegalArgumentException(
                    "Parameter '" + parameterKey + "' is required and cannot be disabled");
        }
        // No-op short-circuit so we don't ping the ML service when nothing changed.
        if (setting.isEnabled() == enabled) {
            return setting;
        }

        setting.setEnabled(enabled);
        setting.setUpdatedBy(actor);
        AiParameterSetting saved = repo.save(setting);

        try {
            pushToMlService(currentToggleMap());
            log.info("[PARAMS] {} → {} (by user={})", parameterKey, enabled, actor != null ? actor.getId() : "?");
        } catch (Exception e) {
            log.warn("[PARAMS] DB updated but ML service push failed for {}: {}", parameterKey, e.getMessage());
        }
        return saved;
    }

    private void pushToMlService(Map<String, Boolean> toggles) {
        String httpUrl = props.getMlService().getHttpUrl();
        if (httpUrl == null || httpUrl.isBlank()) {
            log.debug("[PARAMS] ML_SERVICE_HTTP_URL not configured — skipping push");
            return;
        }
        RestClient client = RestClient.builder()
                .baseUrl(httpUrl.replaceAll("/+$", ""))
                .build();
        client.put()
                .uri("/admin/parameters")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("toggles", toggles))
                .retrieve()
                .toBodilessEntity();
    }
}
