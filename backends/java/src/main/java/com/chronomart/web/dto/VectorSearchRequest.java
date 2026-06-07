package com.chronomart.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/vector/search}. Mirrors
 * {@code contracts/openapi.yaml#/components/schemas/VectorSearchRequest}.
 *
 * <p>The vector field is {@code float[]} (not {@code List<Float>}) so Jackson
 * deserializes the incoming JSON array directly into the canonical primitive shape
 * the SDK serializer pushes onto the wire. This avoids two bug classes:
 * <ul>
 *   <li>Integer-vs-float JSON parsing — bare {@code [1, 2, 3]} would deserialize to
 *       {@code List<Integer>} unless the parameterized type forces float.</li>
 *   <li>Boxed-vs-primitive serialization paths in the SDK that can silently route
 *       a vector through a code path that fails to use the DiskANN index, producing
 *       correct results at full-scan RU cost.</li>
 * </ul>
 *
 * <p>Dimension validation (must equal {@code ContainerInitializer.VECTOR_DIMENSIONS})
 * happens in {@code VectorSearchRunner}, not via bean validation, so the error message
 * can include both the expected dimension and the actual size.
 *
 * @param container target container name (must be in the allow-list)
 * @param vector    query vector; size must match the container's embedding policy
 * @param k         number of matches to return (1..100, default applied in runner)
 */
public record VectorSearchRequest(
    @NotBlank String container,
    @NotNull float[] vector,
    @Min(1) @Max(100) Integer k
) {
}
