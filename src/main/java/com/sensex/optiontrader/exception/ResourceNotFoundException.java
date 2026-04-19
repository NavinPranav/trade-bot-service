package com.sensex.optiontrader.exception;
import lombok.Getter;
@Getter public class ResourceNotFoundException extends RuntimeException {
    private final String resourceName; private final String fieldName; private final Object fieldValue;
    public ResourceNotFoundException(String r, String f, Object v) { super(String.format("%s not found with %s: '%s'",r,f,v)); resourceName=r; fieldName=f; fieldValue=v; }
}