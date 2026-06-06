package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.List;

/**
 * POST /batch response.
 *
 * <ul>
 *   <li>{@code success} — {@code true} when the batch's transaction committed.</li>
 *   <li>{@code statusCode} — the Cosmos batch-level status (200 on success; 409 on
 *       optimistic-concurrency violation; whatever-the-failing-op-returned for other
 *       transactional aborts).</li>
 *   <li>{@code results} — per-op statuses. On rollback, the failing op shows its real
 *       error (e.g. 412 PreconditionFailed) and the other ops show {@code 424}
 *       (FailedDependency) per Cosmos transactional batch semantics.</li>
 * </ul>
 *
 * <p>Note: the controller maps {@code success=false} to <b>HTTP 409</b> regardless of the
 * body {@code statusCode}, per OpenAPI. The body preserves the SDK's status so callers can
 * distinguish 412 (etag mismatch) from 404 (replace-not-found) from 409 (duplicate key).
 */
@JsonInclude(Include.NON_NULL)
public record BatchResponse(
    int statusCode,
    boolean success,
    List<BulkResultItem> results,
    double requestCharge
) {}
