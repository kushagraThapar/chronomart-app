package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * A single Cosmos patch operation.
 *
 * <ul>
 *   <li>{@code op}: one of {@code add}, {@code set}, {@code replace}, {@code remove},
 *       {@code increment}, {@code move}. All six are natively supported by the Java SDK
 *       (azure-cosmos 4.80.0).</li>
 *   <li>{@code path}: JSON-pointer-like absolute path (must start with {@code /}).</li>
 *   <li>{@code value}: required for {@code add}/{@code set}/{@code replace}/{@code increment};
 *       must be omitted for {@code remove}/{@code move}. {@code increment} requires a
 *       numeric value (Long for integral, Double for fractional, preserves precision).</li>
 *   <li>{@code from}: required for {@code move} only — the source path. The Cosmos
 *       semantics are {@code move(from, path)} (i.e. the value is moved from {@code from}
 *       to {@code path}).</li>
 * </ul>
 */
@JsonInclude(Include.NON_NULL)
public record PatchOperation(
    @NotBlank
    @Pattern(regexp = "^(add|set|replace|remove|increment|move)$",
             message = "op must be one of: add, set, replace, remove, increment, move")
    String op,

    @NotBlank
    @Pattern(regexp = "^/.*", message = "path must start with /")
    String path,

    Object value,

    String from
) {}
