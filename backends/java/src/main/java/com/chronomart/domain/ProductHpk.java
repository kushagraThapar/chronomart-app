package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.List;
import java.util.Map;

/**
 * Hierarchical-PK product variant.
 *
 * <p>Partition key is the 3-level tuple {@code (/sellerId, /categoryId, /id)}. The leaf
 * {@code /id} is required so that fully-qualified point reads route to a single physical
 * partition; partial-prefix queries (by {@code sellerId} alone, or by
 * {@code sellerId + categoryId}) still benefit from hierarchical prefix routing.
 * Mirrors {@code contracts/domain.md#ProductHpk}.
 */
@JsonInclude(Include.NON_NULL)
public record ProductHpk(
    String id,
    String sellerId,
    String categoryId,
    String name,
    String brand,
    String model,
    Double priceUsd,
    String currency,
    Map<String, Object> attributes,
    List<String> tags
) {}
