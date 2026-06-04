package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.List;
import java.util.Map;

/**
 * Hierarchical-PK product variant (PK = /sellerId, /categoryId). Mirrors
 * {@code contracts/domain.md#ProductHpk}.
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
