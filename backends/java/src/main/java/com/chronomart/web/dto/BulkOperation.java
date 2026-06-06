package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

/**
 * A single bulk operation: {@code op} verb + per-op {@code partitionKey} + payload.
 *
 * <p>Operation-specific shape rules are enforced in {@code BulkRunner} (where we have the
 * context to give a precise error) rather than in DTO annotations:
 * <ul>
 *   <li><b>create / upsert</b>: {@code document} required; if {@code id} is set and
 *       {@code document.id} is also set they must agree</li>
 *   <li><b>replace</b>: {@code document} required; id resolved from {@code id} or
 *       {@code document.id}; both may be set but must agree</li>
 *   <li><b>delete</b>: {@code id} required (or {@code document.id}); {@code document} may
 *       be {@code null}</li>
 * </ul>
 *
 * {@code partitionKey} is either a {@link String} (single-level) or a {@code List<Object>}
 * (hierarchical levels) — see {@link com.chronomart.repo.ContainerAllowList#parse}.
 */
@JsonInclude(Include.NON_NULL)
public record BulkOperation(
    @NotBlank
    @Pattern(regexp = "^(create|upsert|replace|delete)$",
             message = "op must be one of: create, upsert, replace, delete")
    String op,

    Object partitionKey,

    Map<String, Object> document,

    String id
) {}
