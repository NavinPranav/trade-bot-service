package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.model.entity.AiParameterSetting;
import com.sensex.optiontrader.model.entity.User;
import com.sensex.optiontrader.repository.UserRepository;
import com.sensex.optiontrader.security.UserPrincipal;
import com.sensex.optiontrader.service.AiParameterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin REST surface for the AI parameter toggles (Phase 4.4).
 *
 * <ul>
 *   <li>{@code GET /api/admin/ai-parameters} — list all parameters with their
 *       current state. Used by the Settings → AI Management → Parameters tab.</li>
 *   <li>{@code PUT /api/admin/ai-parameters/{key}} — flip one toggle. Required
 *       parameters return 400.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/ai-parameters")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminAiParameterController {

    private final AiParameterService service;
    private final UserRepository userRepo;

    @GetMapping
    public ResponseEntity<?> list() {
        List<AiParameterSetting> all = service.listAll();
        return ResponseEntity.ok(Map.of(
                "parameters", all.stream().map(AdminAiParameterController::toDto).toList()
        ));
    }

    @PutMapping("/{key}")
    public ResponseEntity<?> update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String key,
            @RequestBody Map<String, Object> body) {
        Object enabledRaw = body.get("enabled");
        if (!(enabledRaw instanceof Boolean enabled)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "field 'enabled' must be a boolean"));
        }
        User user = principal != null
                ? userRepo.findById(principal.getId()).orElse(null)
                : null;
        try {
            AiParameterSetting saved = service.updateEnabled(key, enabled, user);
            return ResponseEntity.ok(toDto(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static Map<String, Object> toDto(AiParameterSetting s) {
        // LinkedHashMap so the JSON keys preserve a sensible order — easier to
        // diff in admin tooling and screenshots.
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", s.getId());
        dto.put("key", s.getParameterKey());
        dto.put("displayName", s.getDisplayName());
        dto.put("description", s.getDescription());
        dto.put("enabled", s.isEnabled());
        dto.put("required", s.isRequired());
        dto.put("sortOrder", s.getSortOrder());
        dto.put("updatedAt", s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null);
        return dto;
    }
}
