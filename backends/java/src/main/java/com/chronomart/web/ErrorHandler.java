package com.chronomart.web;

import com.azure.cosmos.CosmosException;
import com.chronomart.web.dto.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

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
            cosmosCode(status),
            cleanCosmosMessage(ex.getMessage()),
            status,
            ex.getSubStatusCode(),
            ex.getActivityId(),
            "java",
            details
        );
        // Full diagnostics still get logged for support; the client-facing message stays
        // compact so we don't leak SDK internals into every error response body.
        log.warn("CosmosException status={} substatus={} activityId={} msg={}",
            status, ex.getSubStatusCode(), ex.getActivityId(), ex.getMessage());
        return ResponseEntity.status(status).body(new ErrorResponse(err));
    }

    private static String cosmosCode(int status) {
        return switch (status) {
            case 400 -> "BadRequest";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "NotFound";
            case 408 -> "RequestTimeout";
            case 409 -> "Conflict";
            case 412 -> "PreconditionFailed";
            case 413 -> "RequestEntityTooLarge";
            case 429 -> "TooManyRequests";
            case 503 -> "ServiceUnavailable";
            default  -> "CosmosError";
        };
    }

    /**
     * In azure-cosmos 4.x, {@link CosmosException#getMessage()} returns a JSON envelope
     * like {@code {"innerErrorMessage":"...", "cosmosDiagnostics":{...}}}. Surface only
     * the human-readable inner message to clients; the diagnostics are already logged
     * and exposed via {@code details.requestCharge} / {@code activityId}.
     */
    private static String cleanCosmosMessage(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{")) return raw;
        int idx = trimmed.indexOf("\"innerErrorMessage\"");
        if (idx < 0) return raw;
        int colon = trimmed.indexOf(':', idx);
        int firstQuote = trimmed.indexOf('"', colon + 1);
        if (firstQuote < 0) return raw;
        int closeQuote = firstQuote + 1;
        while (closeQuote < trimmed.length()) {
            char c = trimmed.charAt(closeQuote);
            if (c == '\\') { closeQuote += 2; continue; }
            if (c == '"') break;
            closeQuote++;
        }
        if (closeQuote >= trimmed.length()) return raw;
        return trimmed.substring(firstQuote + 1, closeQuote);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of("BadRequest", ex.getMessage(), 400));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
            .map(v -> v.getPropertyPath() + " " + v.getMessage())
            .collect(Collectors.joining("; "));
        if (message.isEmpty()) {
            message = "Request validation failed";
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("violations", ex.getConstraintViolations().stream()
            .map(this::toViolationEntry)
            .toList());
        ErrorResponse.Error err = new ErrorResponse.Error(
            "ValidationFailed",
            message,
            HttpStatus.BAD_REQUEST.value(),
            null,
            null,
            "java",
            details
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(err));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(WebExchangeBindException ex) {
        String message = ex.getBindingResult().getAllErrors().stream()
            .map(err -> {
                if (err instanceof org.springframework.validation.FieldError fe) {
                    return fe.getField() + " " + fe.getDefaultMessage();
                }
                return err.getObjectName() + " " + err.getDefaultMessage();
            })
            .collect(Collectors.joining("; "));
        if (message.isEmpty()) {
            message = "Request body validation failed";
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fieldErrors", ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("field", fe.getField());
                entry.put("message", fe.getDefaultMessage());
                if (fe.getRejectedValue() != null) {
                    entry.put("rejectedValue", fe.getRejectedValue().toString());
                }
                return entry;
            })
            .toList());
        ErrorResponse.Error err = new ErrorResponse.Error(
            "ValidationFailed",
            message,
            HttpStatus.BAD_REQUEST.value(),
            null,
            null,
            "java",
            details
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(err));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ErrorResponse> handleInput(ServerWebInputException ex) {
        // Triggered by malformed JSON bodies, missing required path/query params, type
        // mismatches, etc. Surface as a structured 400 instead of WebFlux's default HTML.
        String reason = ex.getReason() != null ? ex.getReason() : "Invalid request";
        Throwable cause = ex.getMostSpecificCause();
        if (cause != null && cause != ex) {
            reason = reason + ": " + cause.getMessage();
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.of("BadRequest", reason, 400));
    }

    private Map<String, Object> toViolationEntry(ConstraintViolation<?> v) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path", v.getPropertyPath().toString());
        entry.put("message", v.getMessage());
        if (v.getInvalidValue() != null) {
            entry.put("invalidValue", v.getInvalidValue().toString());
        }
        return entry;
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ErrorResponse> handleAny(Throwable ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.of("InternalError", ex.getMessage(), 500));
    }
}
