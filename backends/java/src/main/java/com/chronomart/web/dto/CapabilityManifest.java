package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.List;
import java.util.Map;

/**
 * Capability manifest advertised by the backend at {@code GET /api/v1/_meta/capabilities}.
 * The gateway caches this for 60s and the UI uses it to gate page actions.
 *
 * <p>Values must be honest — never advertise a feature whose endpoint will return 501.
 */
@JsonInclude(Include.NON_NULL)
public record CapabilityManifest(
    String sdk,
    String sdkVersion,
    List<String> apiVersions,
    Map<String, Object> features,
    Map<String, Object> limits,
    String vectorEmbeddingProvider
) {}
