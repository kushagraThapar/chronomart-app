package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.time.Instant;

/**
 * Product review. PK = /productId. Mirrors {@code contracts/domain.md#Review}.
 */
@JsonInclude(Include.NON_NULL)
public record Review(
    String id,
    String productId,
    String customerId,
    Integer rating,
    String title,
    String body,
    Instant createdAt
) {}
