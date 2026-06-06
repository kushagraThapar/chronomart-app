package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Hierarchical-PK product variant (PK = 3-level tuple {@code /sellerId, /categoryId, /id}).
 * Mirrors {@code contracts/domain.md#ProductHpk} and the same OpenAPI {@code Product} schema
 * used at {@code /products-hpk/{sellerId}/{categoryId}/{id}}.
 *
 * <p>The leaf {@code /id} makes a fully-qualified point read single-partition; partial-prefix
 * queries (by {@code sellerId} alone, or by {@code sellerId + categoryId}) still benefit from
 * hierarchical prefix routing.
 *
 * <p>Bean-validation note: {@code categoryId} is required here even though the OpenAPI
 * {@code Product} schema lists it as optional — it's a non-negotiable PK level for this
 * container, so we reject documents missing it before they reach Cosmos.
 */
@JsonInclude(Include.NON_NULL)
public record ProductHpk(
    @NotBlank String id,
    @NotBlank String sellerId,
    @NotBlank String categoryId,
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
