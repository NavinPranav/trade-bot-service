package com.sensex.optiontrader.model.dto.request;

import com.sensex.optiontrader.model.enums.UserRole;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateUserRoleRequest {
    @NotNull
    private UserRole role;
}
