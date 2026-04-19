package com.sensex.optiontrader.model.dto.request;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data public class LoginRequest { @Email @NotBlank private String email; @NotBlank private String password; }