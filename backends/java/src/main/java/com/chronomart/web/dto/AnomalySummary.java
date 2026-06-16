package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.List;
import java.util.Map;

/**
 * Roll-up of the anomalies found in a run, attached to {@link WorkloadProgress}. Mirrors
 * {@code contracts/openapi.yaml#/components/schemas/AnomalySummary}.
 *
 * <p>{@code samples} carries only the first N anomalies (bounded) so the progress payload stays
 * small even when a broken SDK floods the run; the full list is paged via
 * {@code GET /workloads/{runId}/anomalies}.
 *
 * @param total       total anomalies recorded
 * @param errorCount  count with severity {@code ERROR} (definite bugs) — this is what the CI gate reads
 * @param warnCount   count with severity {@code WARN}
 * @param byCode      anomaly count keyed by {@code code}
 * @param samples     first N anomalies, in detection order
 */
@JsonInclude(Include.NON_NULL)
public record AnomalySummary(
    long total,
    long errorCount,
    long warnCount,
    Map<String, Long> byCode,
    List<WorkloadAnomaly> samples
) {
}
