package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.List;
import java.util.Map;

/**
 * Product listing (PK = /sellerId). Mirrors {@code contracts/domain.md#Product}.
 */
@JsonInclude(Include.NON_NULL)
public record Product(
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
