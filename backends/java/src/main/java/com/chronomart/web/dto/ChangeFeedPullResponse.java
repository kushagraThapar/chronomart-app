package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.List;

/**
 * Mirrors {@code contracts/openapi.yaml#/components/schemas/ChangeFeedPullResponse}.
 *
 * <p>{@code continuation} is ALWAYS returned (even on a caught-up page) so the caller can
 * poll again. {@code notModified} is {@code true} when no items were available at this
 * point in time (Cosmos HTTP 304 semantics); callers should wait before polling again.
 *
 * <p>This is intentionally different from {@code QueryResponse}'s pagination model: change
 * feed is a tail-pulling iterator with no "end", whereas a query has a definite end and
 * uses {@code null continuation} to signal completion.
 */
@JsonInclude(Include.NON_NULL)
public record ChangeFeedPullResponse(
    List<Object> items,
    String continuation,
    Boolean notModified,
    Double requestCharge
) {}
