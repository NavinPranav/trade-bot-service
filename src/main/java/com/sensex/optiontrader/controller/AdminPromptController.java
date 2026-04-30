package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.model.entity.AiPrompt;
import com.sensex.optiontrader.model.entity.User;
import com.sensex.optiontrader.repository.UserRepository;
import com.sensex.optiontrader.security.UserPrincipal;
import com.sensex.optiontrader.service.AiPromptService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/prompts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminPromptController {

    private final AiPromptService promptService;
    private final UserRepository userRepo;

    @GetMapping
    public ResponseEntity<?> history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String label) {
        Page<AiPrompt> results = promptService.getHistory(page, size, label);
        return ResponseEntity.ok(Map.of(
                "prompts", results.getContent().stream().map(this::toDto).toList(),
                "totalElements", results.getTotalElements(),
                "totalPages", results.getTotalPages(),
                "page", results.getNumber()
        ));
    }

    @GetMapping("/active")
    public ResponseEntity<?> active() {
        return promptService.getActive()
                .map(p -> ResponseEntity.ok((Object) toDto(p)))
                .orElse(ResponseEntity.ok(Map.of("active", false)));
    }

    @PostMapping
    public ResponseEntity<?> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, String> body) {
        String label = body.getOrDefault("label", "").trim();
        String promptText = body.getOrDefault("prompt_text", "").trim();

        if (label.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "label is required"));
        }
        if (promptText.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "prompt_text is required"));
        }
        if (!promptText.contains("{target_minutes}")) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "prompt_text must contain the {target_minutes} dynamic variable"));
        }

        User user = userRepo.findById(principal.getId()).orElseThrow();
        AiPrompt saved = promptService.saveAndActivate(label, promptText, user);
        return ResponseEntity.ok(toDto(saved));
    }

    private Map<String, Object> toDto(AiPrompt p) {
        return Map.of(
                "id", p.getId(),
                "label", p.getLabel(),
                "promptText", p.getPromptText(),
                "isActive", p.isActive(),
                "createdBy", p.getCreatedBy() != null ? p.getCreatedBy().getName() : "system",
                "createdAt", p.getCreatedAt() != null ? p.getCreatedAt().toString() : ""
        );
    }
}
