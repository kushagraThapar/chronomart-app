package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Cart line item. PK = /customerId. Cart container has defaultTTL=7d. Each item may also
 * set its own {@code ttl} (seconds). Mirrors {@code contracts/domain.md#CartItem}.
 */
@JsonInclude(Include.NON_NULL)
public record CartItem(
    String id,
    String customerId,
    String productId,
    Integer quantity,
    Integer ttl
) {}
