package com.sensex.optiontrader.service;

import com.sensex.optiontrader.exception.BadRequestException;
import com.sensex.optiontrader.exception.ResourceNotFoundException;
import com.sensex.optiontrader.model.dto.response.UserAdminResponse;
import com.sensex.optiontrader.model.entity.User;
import com.sensex.optiontrader.model.enums.UserRole;
import com.sensex.optiontrader.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepo;

    public List<UserAdminResponse> listUsers(int page, int size) {
        Page<User> p = userRepo.findAll(PageRequest.of(page, Math.min(Math.max(size, 1), 100)));
        return p.getContent().stream().map(this::toDto).toList();
    }

    @Transactional
    public UserAdminResponse updateRole(Long userId, UserRole newRole) {
        User u = userRepo.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        if (u.getRole() == UserRole.ADMIN && newRole != UserRole.ADMIN) {
            if (userRepo.countByRole(UserRole.ADMIN) <= 1) {
                throw new BadRequestException("Cannot remove the last admin");
            }
        }
        u.setRole(newRole);
        userRepo.save(u);
        return toDto(u);
    }

    private UserAdminResponse toDto(User u) {
        return UserAdminResponse.builder()
                .id(u.getId())
                .email(u.getEmail())
                .name(u.getName())
                .role(u.getRole() != null ? u.getRole().name() : UserRole.USER.name())
                .build();
    }
}
