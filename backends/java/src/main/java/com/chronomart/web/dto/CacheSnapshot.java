package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.time.Instant;
import java.util.List;

/**
 * Mirrors {@code contracts/openapi.yaml#/components/schemas/CacheSnapshot}.
 *
 * <p>"Cache" is best-effort introspection — the Cosmos SDK does not expose its internal
 * PK-range or container caches via public API, so what we return is a snapshot derived
 * from {@code container.read()} + {@code container.getFeedRanges()} for each
 * allow-listed container. Specifically:
 *
 * <ul>
 *   <li><b>containerCache</b> — one row per allow-listed container. {@code snapshotAt} is
 *       the moment we built the row (NOT when the SDK loaded it); {@code error} is
 *       populated when the container wasn't readable (most common cause: container not
 *       yet provisioned, e.g. {@code ProductVectors} pre-PR8).</li>
 *   <li><b>pkRangeCache</b> — one row per container that read successfully. Each range
 *       exposes only the SDK-provided opaque token today; {@code id/minInclusive/
 *       maxExclusive} stay null until we add internal-SDK introspection.</li>
 * </ul>
 */
@JsonInclude(Include.NON_NULL)
public record CacheSnapshot(
    List<PkRangeCacheEntry> pkRangeCache,
    List<ContainerCacheEntry> containerCache
) {

    @JsonInclude(Include.NON_NULL)
    public record ContainerCacheEntry(
        String database,
        String container,
        String rid,
        Instant snapshotAt,
        String error
    ) {}

    @JsonInclude(Include.NON_NULL)
    public record PkRangeCacheEntry(
        String containerRid,
        List<RangeEntry> ranges
    ) {}

    @JsonInclude(Include.NON_NULL)
    public record RangeEntry(
        String id,
        String minInclusive,
        String maxExclusive,
        String opaque
    ) {}
}
