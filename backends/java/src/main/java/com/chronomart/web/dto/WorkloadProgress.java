package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.time.Instant;
import java.util.List;

/**
 * Live progress + final summary of a workload run. The same DTO serves both
 * {@code GET /workloads/{runId}} (mid-flight) and {@code GET /workloads} (history of
 * completed runs) — the {@code status} field disambiguates.
 *
 * <p>{@code status} is one of: {@code PENDING} (registered but not started),
 * {@code RUNNING}, {@code COMPLETED} (duration elapsed), {@code STOPPED} (operator
 * cancellation), {@code FAILED} (engine-level error, not per-op errors). Per-op
 * failures appear in {@code overall.errorCount} and per-step rows of {@code byStep};
 * a single bad op does not mark the run as {@code FAILED}.
 */
@JsonInclude(Include.NON_NULL)
public record WorkloadProgress(
    String runId,
    String name,
    String status,
    Instant startedAt,
    Instant endedAt,
    long elapsedSec,
    long plannedDurationSec,
    int concurrency,
    WorkloadOpStats overall,
    List<WorkloadOpStats> byStep,
    List<WorkloadTimePoint> timeSeries,
    String errorMessage
) {
}
