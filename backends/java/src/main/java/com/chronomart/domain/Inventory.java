package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Inventory snapshot per (productId, sellerId). PK = /sellerId. Mirrors
 * {@code contracts/domain.md#Inventory}.
 */
@JsonInclude(Include.NON_NULL)
public record Inventory(
    String id,
    String sellerId,
    String productId,
    Integer quantity,
    String warehouseId
) {}
