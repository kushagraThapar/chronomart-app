package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Per-operation result inside a {@link BulkResponse} or {@link BatchResponse}.
 *
 * <ul>
 *   <li>{@code op} — echoed from the input so callers can correlate without indexing.</li>
 *   <li>{@code statusCode} — the per-op Cosmos status. For transactional batch operations
 *       not reached due to rollback, this is {@code 424} (FailedDependency) per Cosmos
 *       semantics.</li>
 *   <li>{@code requestCharge} — per-op RU.</li>
 *   <li>{@code resourceId} — the logical document {@code id} the operation targeted
 *       (resolved from the input). {@code null} for delete-of-unknown-id failure paths.
 *       NOTE: this is NOT the Cosmos system {@code _rid}; the public bulk/batch response
 *       types do not expose that.</li>
 *   <li>{@code error} — human-readable message when the op failed; {@code null} on success.</li>
 * </ul>
 */
@JsonInclude(Include.NON_NULL)
public record BulkResultItem(
    String op,
    int statusCode,
    double requestCharge,
    String resourceId,
    String error
) {}
