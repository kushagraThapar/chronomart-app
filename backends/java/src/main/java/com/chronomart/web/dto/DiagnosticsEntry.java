package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.time.Instant;
import java.util.Map;

/**
 * Mirrors {@code contracts/openapi.yaml#/components/schemas/DiagnosticsEntry}.
 *
 * <p>Captured by {@link com.chronomart.repo.DiagnosticsRecorder} from the Cosmos SDK's
 * {@code CosmosDiagnosticsHandler} callback. Snapshot the context at capture time —
 * the SDK may reuse / mutate the {@code CosmosDiagnosticsContext} object after the
 * handler returns.
 */
@JsonInclude(Include.NON_NULL)
public record DiagnosticsEntry(
    Instant timestamp,
    String operation,
    Double durationMs,
    Double requestCharge,
    Integer statusCode,
    String activityId,
    Map<String, Object> diagnostics
) {}
