package com.chronomart.web;

import com.azure.cosmos.CosmosException;
import com.chronomart.web.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralized error mapping so every backend returns the contract's
 * {@code ErrorResponse} shape and propagates Cosmos diagnostics fields.
 */
@ControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(CosmosException.class)
    public ResponseEntity<ErrorResponse> handleCosmos(CosmosException ex) {
        int status = ex.getStatusCode() > 0 ? ex.getStatusCode() : HttpStatus.INTERNAL_SERVER_ERROR.value();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requestCharge", ex.getRequestCharge());
        if (ex.getRetryAfterDuration() != null) {
            details.put("retryAfterMs", ex.getRetryAfterDuration().toMillis());
        }
        ErrorResponse.Error err = new ErrorResponse.Error(
            "CosmosError",
            ex.getMessage(),
            status,
            ex.getSubStatusCode(),
            ex.getActivityId(),
            "java",
            details
        );
        log.warn("CosmosException status={} substatus={} activityId={} msg={}",
            status, ex.getSubStatusCode(), ex.getActivityId(), ex.getMessage());
        return ResponseEntity.status(status).body(new ErrorResponse(err));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of("BadRequest", ex.getMessage(), 400));
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ErrorResponse> handleAny(Throwable ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.of("InternalError", ex.getMessage(), 500));
    }
}
