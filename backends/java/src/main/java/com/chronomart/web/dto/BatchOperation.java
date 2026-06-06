package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

/**
 * A single operation inside a transactional batch.
 *
 * <p>Differences from {@link BulkOperation}:
 * <ul>
 *   <li>Adds {@code ifMatchEtag} for per-op optimistic concurrency.</li>
 *   <li>{@code partitionKey} must be {@code null} — the batch's partition key applies to
 *       every operation. Setting a per-op PK is rejected by the runner with HTTP 400.</li>
 * </ul>
 */
@JsonInclude(Include.NON_NULL)
public record BatchOperation(
    @NotBlank
    @Pattern(regexp = "^(create|upsert|replace|delete)$",
             message = "op must be one of: create, upsert, replace, delete")
    String op,

    Object partitionKey,

    Map<String, Object> document,

    String id,

    String ifMatchEtag
) {}
