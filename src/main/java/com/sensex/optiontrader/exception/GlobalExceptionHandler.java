package com.sensex.optiontrader.exception;

import com.sensex.optiontrader.model.dto.response.ApiErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.HashMap;

@Slf4j @RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> h1(ResourceNotFoundException e) { return r(HttpStatus.NOT_FOUND, e.getMessage()); }
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiErrorResponse> h2(BadRequestException e) { return r(HttpStatus.BAD_REQUEST, e.getMessage()); }
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiErrorResponse> h3(UnauthorizedException e) { return r(HttpStatus.UNAUTHORIZED, e.getMessage()); }
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiErrorResponse> hForbidden(ForbiddenException e) { return r(HttpStatus.FORBIDDEN, e.getMessage()); }
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> h4(BadCredentialsException e) { return r(HttpStatus.UNAUTHORIZED, "Invalid credentials"); }
    @ExceptionHandler(MlServiceUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> h5(MlServiceUnavailableException e) { log.error("ML unavailable: {}", e.getMessage()); return r(HttpStatus.SERVICE_UNAVAILABLE, "Prediction service unavailable"); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> h6(MethodArgumentNotValidException e) {
        var m = new HashMap<String,String>(); e.getBindingResult().getAllErrors().forEach(err -> m.put(((FieldError)err).getField(), err.getDefaultMessage()));
        return ResponseEntity.badRequest().body(ApiErrorResponse.builder().status(400).message("Validation failed").error("BAD_REQUEST").timestamp(LocalDateTime.now()).validationErrors(m).build());
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> h7(Exception e) { log.error("Unhandled", e); return r(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error"); }
    private ResponseEntity<ApiErrorResponse> r(HttpStatus s, String m) {
        return ResponseEntity.status(s).body(ApiErrorResponse.builder().status(s.value()).message(m).error(s.getReasonPhrase()).timestamp(LocalDateTime.now()).build());
    }
}