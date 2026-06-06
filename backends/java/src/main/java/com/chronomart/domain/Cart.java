package com.chronomart.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * Shopping cart, one document per customer (PK = {@code /customerId}). Mirrors
 * {@code contracts/openapi.yaml#/components/schemas/Cart}.
 *
 * <p>The {@code Cart} container has a 7-day {@code defaultTimeToLive} provisioned by
 * {@link com.chronomart.repo.ContainerInitializer}, so an idle cart eventually expires.
 * Per-document {@code ttl} (seconds) overrides the container default for this document:
 * {@code -1} disables TTL for this doc, a positive integer shortens it. A {@code null}
 * or omitted {@code ttl} means "inherit the container default" on the replaced document —
 * an upsert that omits {@code ttl} clears any previously-set per-doc override and the
 * document reverts to the container's 7-day default.
 */
@JsonInclude(Include.NON_NULL)
public record Cart(
    @NotBlank String id,
    @NotBlank String customerId,
    @NotNull List<@Valid CartItem> items,
    Instant updatedAt,
    Integer ttl
) {}
