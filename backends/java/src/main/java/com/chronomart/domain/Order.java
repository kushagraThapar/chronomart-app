package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;

/**
 * Customer order. Partition key is the 3-level tuple {@code (/customerId, /yearMonth, /id)};
 * the leaf {@code /id} makes a fully-qualified point read single-partition while queries
 * scoped to a customer (or customer-month) still get hierarchical prefix routing. Mirrors
 * {@code contracts/openapi.yaml#/components/schemas/Order}.
 *
 * <p>Bean-validation matches the OpenAPI {@code required} list
 * ({@code id, customerId, yearMonth, items, totalUsd}). {@code status} is optional but,
 * when present, must be one of the documented enum values; {@code yearMonth} must match
 * the {@code YYYY-MM} pattern declared in the OpenAPI path parameter.
 */
@JsonInclude(Include.NON_NULL)
public record Order(
    @NotBlank String id,
    @NotBlank String customerId,
    @NotBlank @Pattern(regexp = "^[0-9]{4}-[0-9]{2}$",
                       message = "yearMonth must be YYYY-MM") String yearMonth,
    @Pattern(regexp = "^(pending|paid|shipped|delivered|cancelled)$",
             message = "status must be one of pending|paid|shipped|delivered|cancelled") String status,
    @NotEmpty List<@Valid OrderItem> items,
    @NotNull Double totalUsd,
    Instant createdAt,
    Instant shippedAt
) {
    /**
     * Embedded order-line item. Mirrors {@code OpenAPI#/components/schemas/OrderItem}.
     * {@code sellerId} is optional (denormalised convenience for analytics).
     */
    public record OrderItem(
        @NotBlank String productId,
        String sellerId,
        @NotNull @Min(1) Integer qty,
        @NotNull Double unitPriceUsd
    ) {}
}
