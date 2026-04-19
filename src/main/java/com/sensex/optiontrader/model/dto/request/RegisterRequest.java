package com.sensex.optiontrader.model.dto.request;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data public class RegisterRequest { @NotBlank private String name; @Email @NotBlank private String email; @NotBlank @Size(min=8,max=128) private String password; }