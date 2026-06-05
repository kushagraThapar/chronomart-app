package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.time.Instant;
import java.util.List;

/**
 * Customer order.
 *
 * <p>Partition key is the 3-level tuple {@code (/customerId, /yearMonth, /id)}. The leaf
 * {@code /id} makes a full-tuple point read single-partition; queries scoped to a customer
 * (or a customer-month) still get hierarchical prefix routing. Mirrors
 * {@code contracts/domain.md#Order}.
 */
@JsonInclude(Include.NON_NULL)
public record Order(
    String id,
    String customerId,
    String yearMonth,
    Instant placedAt,
    String status,
    Double totalUsd,
    List<Line> lines
) {
    public record Line(String productId, String sellerId, Integer quantity, Double priceUsd) {}
}
