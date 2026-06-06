package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * POST /bulk payload.
 *
 * <p>{@code maxConcurrency} maps to {@code CosmosBulkExecutionOptions.setMaxMicroBatchConcurrency}.
 * The SDK enforces the range [1, 5] inclusive. Use {@code null} or {@code -1} to skip the
 * option entirely (SDK default applies).
 *
 * <p>Operation cap is {@code MetaController.limits.maxBulkItems = 100} (mirrored here so
 * malformed callers see a structured 400 before any SDK call).
 */
@JsonInclude(Include.NON_NULL)
public record BulkRequest(
    @NotBlank String container,

    @NotEmpty
    @Size(max = 100, message = "operations cannot exceed 100 items per bulk request")
    List<@Valid BulkOperation> operations,

    @Min(value = -1, message = "maxConcurrency must be -1 (SDK default) or 1..5")
    Integer maxConcurrency
) {}
