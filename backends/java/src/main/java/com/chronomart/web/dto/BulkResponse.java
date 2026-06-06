package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.List;

/**
 * POST /bulk response. {@code results} mirrors input order (results from the SDK Flux are
 * sorted by per-op context index before being returned).
 */
@JsonInclude(Include.NON_NULL)
public record BulkResponse(
    List<BulkResultItem> results,
    double totalRequestCharge
) {}
