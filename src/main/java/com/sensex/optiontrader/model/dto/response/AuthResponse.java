package com.sensex.optiontrader.model.dto.response;
import lombok.*;
@Data @Builder @AllArgsConstructor public class AuthResponse { private String accessToken; private String refreshToken; private String tokenType; private long expiresIn; private String email; private String name; private String role; }