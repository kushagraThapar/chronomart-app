package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.time.Instant;

/**
 * Marketplace customer (PK = /id). Mirrors {@code contracts/domain.md#Customer}.
 */
@JsonInclude(Include.NON_NULL)
public record Customer(
    String id,
    String name,
    String email,
    String country,
    String tier,
    Instant createdAt
) {}
