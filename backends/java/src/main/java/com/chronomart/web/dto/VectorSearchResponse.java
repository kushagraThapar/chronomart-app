package com.chronomart.web.dto;

import java.util.List;

/**
 * Response body for {@code POST /api/v1/vector/search}. Mirrors
 * {@code contracts/openapi.yaml#/components/schemas/VectorSearchResponse}.
 *
 * <p>Matches are returned most-similar-first by Cosmos. The {@code score} field's
 * meaning (similarity vs distance) depends on the container's distance function — see
 * {@link VectorMatch}. {@code requestCharge} is the RU consumed by the underlying
 * single-page {@code byPage().next()} call; a wildly higher value than expected for a
 * small container is a signal the DiskANN index was not used.
 */
public record VectorSearchResponse(
    List<VectorMatch> matches,
    double requestCharge
) {
}
