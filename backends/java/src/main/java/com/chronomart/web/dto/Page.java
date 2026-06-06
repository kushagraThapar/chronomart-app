package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.List;

/**
 * Generic paged result. Mirrors {@code contracts/openapi.yaml#/components/schemas/ProductPage}
 * (and the same shape for any other future {@code XxxPage}). {@code continuation} is the
 * opaque Cosmos continuation token for the next page, or {@code null} when there are no more.
 */
@JsonInclude(Include.NON_NULL)
public record Page<T>(List<T> items, String continuation) {}
