package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Inventory snapshot per (productId, sellerId). PK = /sellerId. Mirrors
 * {@code contracts/domain.md#Inventory} and
 * {@code contracts/openapi.yaml#/components/schemas/Inventory}.
 *
 * <p>OpenAPI requires {@code id, sellerId, productId, available}; available {@code >= 0}.
 */
@JsonInclude(Include.NON_NULL)
public record Inventory(
    @NotBlank String id,
    @NotBlank String sellerId,
    @NotBlank String productId,
    @NotNull @Min(0) Integer available,
    @Min(0) Integer reserved,
    Instant updatedAt
) {}
