package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * POST /patch payload. Partial update against a single document.
 *
 * <p>{@code ifMatchEtag} + {@code filterPredicate} are both optional. Cosmos enforces a
 * hard limit of 10 patch operations per request (mirrored here so malformed callers see a
 * structured 400 before any SDK call).
 */
@JsonInclude(Include.NON_NULL)
public record PatchRequest(
    @NotBlank String container,

    @NotBlank String id,

    @NotNull Object partitionKey,

    String ifMatchEtag,

    String filterPredicate,

    @NotEmpty
    @Size(max = 10, message = "operations cannot exceed 10 per patch request (Cosmos limit)")
    List<@Valid PatchOperation> operations
) {}
