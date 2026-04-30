package com.sensex.optiontrader.service;

import com.sensex.optiontrader.config.AppProperties;
import com.sensex.optiontrader.model.entity.AiModel;
import com.sensex.optiontrader.model.entity.AiTool;
import com.sensex.optiontrader.repository.AiModelRepository;
import com.sensex.optiontrader.repository.AiToolRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelService {

    private final AiToolRepository toolRepo;
    private final AiModelRepository modelRepo;
    private final AppProperties props;

    @PostConstruct
    public void syncActiveModelOnStartup() {
        try {
            modelRepo.findByIsActiveTrue().ifPresent(m -> {
                try {
                    pushToMlService(m.getTool().getName(), m.getModelId());
                    log.info("[AI-MODEL] Synced active model '{}' to ML service on startup", m.getModelId());
                } catch (Exception e) {
                    log.warn("[AI-MODEL] Startup ML-service push failed (non-fatal): {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[AI-MODEL] Startup sync skipped — DB not ready yet: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> getAllToolsWithModels() {
        List<AiTool> tools = toolRepo.findAllWithModels();
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiTool tool : tools) {
            Map<String, Object> toolDto = new LinkedHashMap<>();
            toolDto.put("id", tool.getId());
            toolDto.put("name", tool.getName());
            toolDto.put("displayName", tool.getDisplayName());
            toolDto.put("enabled", tool.isEnabled());
            List<Map<String, Object>> models = tool.getModels() == null
                    ? List.of()
                    : tool.getModels().stream().map(m -> {
                        Map<String, Object> dto = new LinkedHashMap<>();
                        dto.put("id", m.getId());
                        dto.put("modelId", m.getModelId());
                        dto.put("displayName", m.getDisplayName());
                        dto.put("enabled", m.isEnabled());
                        dto.put("isActive", m.isActive());
                        return (Map<String, Object>) dto;
                    }).toList();
            toolDto.put("models", models);
            result.add(toolDto);
        }
        return result;
    }

    public Optional<AiModel> getActive() {
        return modelRepo.findByIsActiveTrue();
    }

    /** Snapshot of the active tool name for stamping onto a prediction (null-safe). */
    public String getActiveToolName() {
        return modelRepo.findByIsActiveTrue()
                .map(m -> m.getTool().getName())
                .orElse(null);
    }

    /** Snapshot of the active model id for stamping onto a prediction (null-safe). */
    public String getActiveModelId() {
        return modelRepo.findByIsActiveTrue()
                .map(AiModel::getModelId)
                .orElse(null);
    }

    @Transactional
    public AiModel activate(Long modelId) {
        AiModel model = modelRepo.findById(modelId)
                .orElseThrow(() -> new IllegalArgumentException("AI model not found: " + modelId));
        if (!model.getTool().isEnabled()) {
            throw new IllegalStateException("AI tool is not enabled: " + model.getTool().getName());
        }
        if (!model.isEnabled()) {
            throw new IllegalStateException("AI model is not enabled: " + model.getModelId());
        }
        modelRepo.deactivateAll();
        model.setActive(true);
        model = modelRepo.save(model);

        try {
            pushToMlService(model.getTool().getName(), model.getModelId());
            log.info("[AI-MODEL] Activated '{}' and pushed to ML service", model.getModelId());
        } catch (Exception e) {
            log.warn("[AI-MODEL] Model activated but ML service push failed: {}", e.getMessage());
        }
        return model;
    }

    private void pushToMlService(String toolName, String modelId) {
        String httpUrl = props.getMlService().getHttpUrl();
        if (httpUrl == null || httpUrl.isBlank()) {
            log.debug("[AI-MODEL] ML_SERVICE_HTTP_URL not configured — skipping push");
            return;
        }
        RestClient.builder()
                .baseUrl(httpUrl.replaceAll("/+$", ""))
                .build()
                .put()
                .uri("/admin/model")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tool", toolName, "model_id", modelId))
                .retrieve()
                .toBodilessEntity();
    }
}
