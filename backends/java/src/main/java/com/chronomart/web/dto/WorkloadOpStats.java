package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Snapshot of per-op metrics during or after a workload run. Returned both for the overall
 * run (op="*", container="*") and per-step (op="pointRead", container="Products", ...).
 *
 * <p>Latency fields are milliseconds (double, so sub-ms is preserved at high RPS).
 * {@code totalRu} is the cumulative request charge. {@code opsPerSec} is computed from
 * the run's elapsed wall time, so for an in-progress run it's the running average.
 */
@JsonInclude(Include.NON_NULL)
public record WorkloadOpStats(
    String op,
    String container,
    long count,
    long errorCount,
    double totalRu,
    double opsPerSec,
    double ruPerSec,
    double latencyMeanMs,
    double latencyP50Ms,
    double latencyP95Ms,
    double latencyP99Ms,
    double latencyMaxMs
) {
}
