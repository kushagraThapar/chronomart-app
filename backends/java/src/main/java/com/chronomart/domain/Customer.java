package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * Marketplace customer (PK = /id). Mirrors {@code contracts/domain.md#Customer}
 * and {@code contracts/openapi.yaml#/components/schemas/Customer}.
 *
 * <p>Bean-validation enforces the OpenAPI {@code required} list ({@code id, name}).
 */
@JsonInclude(Include.NON_NULL)
public record Customer(
    @NotBlank String id,
    @NotBlank String name,
    String email,
    String country,
    String tier,
    Instant createdAt
) {}
