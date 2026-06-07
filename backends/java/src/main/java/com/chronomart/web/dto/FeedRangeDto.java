package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Mirrors {@code contracts/openapi.yaml#/components/schemas/FeedRange}.
 *
 * <p>The schema exposes four fields ({@code id}, {@code minInclusive}, {@code maxExclusive},
 * {@code opaque}) but only {@code opaque} round-trips through the SDK today via
 * {@link com.azure.cosmos.models.FeedRange#fromString}. The other three are reserved for
 * the future {@code /_meta/feed-ranges} diagnostics endpoint (PR8) and are intentionally
 * rejected by the change feed runner if populated — so callers don't develop a false sense
 * that they're working when in fact they're being silently ignored.
 */
@JsonInclude(Include.NON_NULL)
public record FeedRangeDto(
    String id,
    String minInclusive,
    String maxExclusive,
    String opaque
) {}
