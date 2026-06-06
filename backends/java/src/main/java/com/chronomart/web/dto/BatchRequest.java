package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * POST /batch payload. Transactional — all operations succeed or all roll back, scoped to
 * a single {@code partitionKey}. Hard limit of 100 operations per Cosmos contract.
 */
@JsonInclude(Include.NON_NULL)
public record BatchRequest(
    @NotBlank String container,

    @NotNull Object partitionKey,

    @NotEmpty
    @Size(max = 100, message = "operations cannot exceed 100 items per batch")
    List<@Valid BatchOperation> operations
) {}
