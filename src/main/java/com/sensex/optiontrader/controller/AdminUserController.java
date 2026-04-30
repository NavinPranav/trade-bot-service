package com.sensex.optiontrader.controller;

import com.sensex.optiontrader.model.dto.request.UpdateUserRoleRequest;
import com.sensex.optiontrader.model.dto.response.UserAdminResponse;
import com.sensex.optiontrader.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(Map.of("users", adminUserService.listUsers(page, size)));
    }

    @PatchMapping("/{id}/role")
    public ResponseEntity<UserAdminResponse> updateRole(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRoleRequest body) {
        return ResponseEntity.ok(adminUserService.updateRole(id, body.getRole()));
    }
}
