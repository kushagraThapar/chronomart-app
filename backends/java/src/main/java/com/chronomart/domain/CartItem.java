package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Embedded cart-line item. Mirrors {@code contracts/openapi.yaml#/components/schemas/CartItem}.
 *
 * <p>This is intentionally a value record (no id, no customerId): a {@link Cart} is the
 * top-level Cosmos document keyed by {@code customerId} and carries an array of
 * {@link CartItem}s inline. Container-level TTL lives on the parent {@code Cart}.
 */
@JsonInclude(Include.NON_NULL)
public record CartItem(
    @NotBlank String productId,
    @NotNull @Min(1) Integer qty,
    Instant addedAt
) {}
