package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * Embedded cart-line item. Mirrors {@code contracts/openapi.yaml#/components/schemas/CartItem}.
 *
 * <p>This is intentionally a value record (no id, no customerId): a {@link Cart} is the
 * top-level Cosmos document keyed by {@code customerId} and carries an array of
 * {@link CartItem}s inline. Container-level TTL lives on the parent {@code Cart}.
 *
 * <p>{@code sellerId} and {@code unitPriceUsd} are an optional price/seller snapshot taken
 * when the item is added from the catalog (the product carries both). Persisting them here
 * lets checkout pre-fill the {@link Order.OrderItem} line — which <em>requires</em>
 * {@code unitPriceUsd} — without a second product lookup. They are nullable so items added
 * by hand (the harness Cart page) or by older clients still validate; {@code @JsonInclude}
 * keeps them off the wire when absent so existing carts round-trip unchanged.
 */
@JsonInclude(Include.NON_NULL)
public record CartItem(
    @NotBlank String productId,
    @NotNull @Min(1) Integer qty,
    Instant addedAt,
    String sellerId,
    @PositiveOrZero Double unitPriceUsd
) {}
