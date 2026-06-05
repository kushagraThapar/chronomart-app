package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Product listing (PK = /sellerId). Mirrors {@code contracts/domain.md#Product}
 * and {@code contracts/openapi.yaml#/components/schemas/Product}.
 *
 * <p>Bean-validation annotations match the OpenAPI {@code required} list
 * ({@code id, sellerId, name, priceUsd}); other fields are optional.
 */
@JsonInclude(Include.NON_NULL)
public record Product(
    @NotBlank String id,
    @NotBlank String sellerId,
    String categoryId,
    @NotBlank String name,
    String brand,
    String model,
    @NotNull Double priceUsd,
    String currency,
    Map<String, Object> attributes,
    List<String> tags,
    List<String> images,
    Instant createdAt,
    Instant updatedAt
) {}
