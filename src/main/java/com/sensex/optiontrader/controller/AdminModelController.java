package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.model.entity.AiModel;
import com.sensex.optiontrader.service.AiModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminModelController {

    private final AiModelService modelService;

    @GetMapping("/ai-tools")
    public ResponseEntity<List<Map<String, Object>>> listTools() {
        return ResponseEntity.ok(modelService.getAllToolsWithModels());
    }

    @PostMapping("/ai-models/{id}/activate")
    public ResponseEntity<?> activate(@PathVariable Long id) {
        try {
            AiModel model = modelService.activate(id);
            return ResponseEntity.ok(Map.of(
                    "id", model.getId(),
                    "modelId", model.getModelId(),
                    "displayName", model.getDisplayName(),
                    "isActive", model.isActive(),
                    "toolName", model.getTool().getName()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
