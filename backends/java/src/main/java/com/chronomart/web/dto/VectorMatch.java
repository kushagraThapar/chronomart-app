package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * One row in {@link VectorSearchResponse#matches()}. Mirrors the inline schema in
 * {@code contracts/openapi.yaml#/components/schemas/VectorSearchResponse}.
 *
 * <p>{@code productId}, {@code sellerId}, and {@code name} are denormalized projections
 * lifted off the vector document — keep them at top level so the marketplace UI can
 * render a result row without a cross-container fetch. They are nullable on the wire
 * via {@link JsonInclude} so vector docs missing one of these fields don't blow up the
 * response (the {@code document} field still has the raw record for inspection).
 *
 * <p>{@code score} is the raw value returned by {@code VectorDistance(c.embedding, @vec)} —
 * its meaning depends on the container's distance function (COSINE → similarity, higher
 * is closer; EUCLIDEAN → distance, lower is closer; DOT_PRODUCT → dot product, higher is
 * closer). The vNext emulator returns cosine similarity (≈ 1.0 for an exact match) for
 * the COSINE config that {@code ProductVectors} uses. Results are always ordered
 * most-similar-first regardless of the underlying sign.
 *
 * @param id         vector document id
 * @param productId  partition-key value (may equal {@code id})
 * @param sellerId   denormalized seller id for UI rendering
 * @param name       denormalized product name for UI rendering
 * @param score      VectorDistance value (lower = closer to the query vector)
 * @param document   full vector document (includes the embedding array)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VectorMatch(
    String id,
    String productId,
    String sellerId,
    String name,
    Double score,
    Map<String, Object> document
) {
}
