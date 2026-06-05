package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Product review. PK = /productId. Mirrors {@code contracts/domain.md#Review} and
 * {@code contracts/openapi.yaml#/components/schemas/Review}.
 *
 * <p>OpenAPI requires {@code id, productId, customerId, rating}; rating is {@code [1,5]}.
 */
@JsonInclude(Include.NON_NULL)
public record Review(
    @NotBlank String id,
    @NotBlank String productId,
    @NotBlank String customerId,
    @NotNull @Min(1) @Max(5) Integer rating,
    String title,
    String body,
    Instant createdAt
) {}
