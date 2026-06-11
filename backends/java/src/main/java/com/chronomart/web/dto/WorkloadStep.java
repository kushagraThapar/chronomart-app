package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * One step in a {@link WorkloadSpec}. The {@code op} is a discriminator handled by
 * {@link com.chronomart.repo.WorkloadEngine}; v1 supports {@code pointRead},
 * {@code query}, and {@code cartUpsert}. Unknown ops fail the spec at start time so
 * the run never enters a partial-success state where some users execute and others 400.
 *
 * <p>{@code params} is intentionally untyped — it's an op-specific bag (e.g. ids,
 * partitionKeys, query text). The engine validates the keys it knows about per op and
 * returns 400 for missing required keys. See {@link com.chronomart.repo.WorkloadEngine}
 * for the per-op param contracts.
 */
@JsonInclude(Include.NON_NULL)
public record WorkloadStep(
    @NotBlank String op,
    @NotBlank String container,
    @Min(1) int weight,
    Map<String, Object> params
) {
}
