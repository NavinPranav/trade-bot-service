package com.sensex.optiontrader.model.dto.response;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import java.time.LocalDateTime;
import java.util.Map;
@Data @Builder @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiErrorResponse { private int status; private String message; private String error; private LocalDateTime timestamp; private Map<String,String> validationErrors; }