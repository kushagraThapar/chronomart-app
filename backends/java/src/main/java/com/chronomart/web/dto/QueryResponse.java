package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.List;
import java.util.Map;

/**
 * Mirrors {@code contracts/openapi.yaml#/components/schemas/QueryResponse}. Items are
 * typed as {@code Object} (any) so the runner can return both document-shaped maps and
 * scalar projections like {@code SELECT VALUE COUNT(1) FROM c}. Callers (UI, contract
 * tests) introspect runtime type as needed.
 */
@JsonInclude(Include.NON_NULL)
public record QueryResponse(
    List<Object> items,
    String continuation,
    Double requestCharge,
    Map<String, Object> diagnostics
) {}
