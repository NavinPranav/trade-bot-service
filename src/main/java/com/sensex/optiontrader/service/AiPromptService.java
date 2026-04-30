package com.sensex.optiontrader.service;

import com.sensex.optiontrader.config.AppProperties;
import com.sensex.optiontrader.model.entity.AiPrompt;
import com.sensex.optiontrader.model.entity.User;
import com.sensex.optiontrader.repository.AiPromptRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiPromptService {

    private final AiPromptRepository promptRepo;
    private final AppProperties props;

    /** Push the current active prompt to the ML service on startup. */
    @PostConstruct
    public void syncActivePromptOnStartup() {
        promptRepo.findByIsActiveTrue().ifPresent(p -> {
            try {
                pushToMlService(p.getPromptText());
                log.info("[PROMPT] Synced active prompt '{}' to ML service on startup", p.getLabel());
            } catch (Exception e) {
                log.warn("[PROMPT] Startup sync to ML service failed: {}", e.getMessage());
            }
        });
    }

    public Page<AiPrompt> getHistory(int page, int size, String label) {
        PageRequest pr = PageRequest.of(page, size);
        return (label == null || label.isBlank())
                ? promptRepo.findAllByOrderByCreatedAtDesc(pr)
                : promptRepo.findByLabelContainingIgnoreCaseOrderByCreatedAtDesc(label.trim(), pr);
    }

    public Optional<AiPrompt> getActive() {
        return promptRepo.findByIsActiveTrue();
    }

    @Transactional
    public AiPrompt saveAndActivate(String label, String promptText, User createdBy) {
        promptRepo.deactivateAll();

        AiPrompt prompt = AiPrompt.builder()
                .label(label)
                .promptText(promptText)
                .isActive(true)
                .createdBy(createdBy)
                .build();
        prompt = promptRepo.save(prompt);

        try {
            pushToMlService(promptText);
            log.info("[PROMPT] Activated prompt '{}' and pushed to ML service", label);
        } catch (Exception e) {
            log.warn("[PROMPT] Prompt saved but ML service push failed: {}", e.getMessage());
        }

        return prompt;
    }

    private void pushToMlService(String promptText) {
        String httpUrl = props.getMlService().getHttpUrl();
        if (httpUrl == null || httpUrl.isBlank()) {
            log.debug("[PROMPT] ML_SERVICE_HTTP_URL not configured — skipping push");
            return;
        }
        RestClient client = RestClient.builder()
                .baseUrl(httpUrl.replaceAll("/+$", ""))
                .build();
        client.put()
                .uri("/admin/prompt")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("prompt_text", promptText))
                .retrieve()
                .toBodilessEntity();
    }
}
