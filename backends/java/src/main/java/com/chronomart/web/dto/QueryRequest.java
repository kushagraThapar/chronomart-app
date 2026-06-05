package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Mirrors {@code contracts/openapi.yaml#/components/schemas/QueryRequest}.
 *
 * <p>{@code partitionKey} accepts either a single string (single-level PK) or an array of
 * strings (hierarchical PK levels). Jackson handles the polymorphic deserialization via
 * {@code Object}; the query runner inspects the runtime type.
 *
 * <p>{@code enableCrossPartition} is opt-in (default {@code false}); when {@code false} and
 * no {@code partitionKey} is supplied, the request is rejected with 400.
 */
@JsonInclude(Include.NON_NULL)
public record QueryRequest(
    @NotBlank String container,
    @NotBlank String query,
    List<Parameter> parameters,
    @JsonAlias({"pk"}) Object partitionKey,
    @Min(1) @Max(1000) Integer pageSize,
    String continuation,
    Boolean enableCrossPartition,
    Integer maxConcurrency
) {

    public int effectivePageSize() {
        return pageSize == null ? 100 : pageSize;
    }

    public boolean crossPartitionEnabled() {
        return Boolean.TRUE.equals(enableCrossPartition);
    }

    @JsonInclude(Include.NON_NULL)
    public record Parameter(String name, Object value) {}
}
