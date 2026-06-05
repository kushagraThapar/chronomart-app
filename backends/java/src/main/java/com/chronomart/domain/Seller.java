package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.time.Instant;

/**
 * Marketplace seller (PK = /id). Mirrors {@code contracts/domain.md#Seller}.
 */
@JsonInclude(Include.NON_NULL)
public record Seller(
    String id,
    String name,
    String country,
    Double rating,
    Instant joinedAt
) {}
