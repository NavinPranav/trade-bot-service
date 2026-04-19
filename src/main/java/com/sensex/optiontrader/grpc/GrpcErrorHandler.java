package com.sensex.optiontrader.grpc;

import com.sensex.optiontrader.exception.BadRequestException;
import com.sensex.optiontrader.exception.MlServiceUnavailableException;
import com.sensex.optiontrader.exception.ResourceNotFoundException;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GrpcErrorHandler {

    public RuntimeException translate(StatusRuntimeException e) {
        var st = e.getStatus();
        String desc = st.getDescription();
        String msg = desc != null && !desc.isBlank() ? desc : "(no description)";
        log.warn("ML gRPC: code={} description={} trailers={}", st.getCode(), msg, e.getTrailers());
        return switch (st.getCode()) {
            case NOT_FOUND -> new ResourceNotFoundException("ML", "id", "unknown");
            case INVALID_ARGUMENT -> new BadRequestException("Invalid: " + msg);
            case UNAVAILABLE, DEADLINE_EXCEEDED -> new MlServiceUnavailableException("ML unavailable: " + msg, e);
            default -> new MlServiceUnavailableException("ML error: " + st.getCode() + ": " + msg, e);
        };
    }
}