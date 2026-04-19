package com.sensex.optiontrader.exception;

public class MlServiceUnavailableException extends RuntimeException {
    public MlServiceUnavailableException(String m) {
        super(m);
    }

    public MlServiceUnavailableException(String m, Throwable cause) {
        super(m, cause);
    }
}