package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.time.Instant;

/**
 * One per-second sample from a workload run, for charting. Captured by a scheduled
 * snapshotter while the run is in progress; the run holds at most ~1800 points
 * (matching the {@link WorkloadSpec#durationSeconds()} cap).
 *
 * <p>All fields are deltas since the previous sample, NOT cumulative — easier for
 * charting (the chart shows RPS / RU/s / errs/s as bar or line values per second).
 * Latency is the mean for that one-second window, not a percentile, to keep the
 * snapshot cheap.
 */
@JsonInclude(Include.NON_NULL)
public record WorkloadTimePoint(
    Instant timestamp,
    long elapsedSec,
    long ops,
    long errors,
    double ru,
    double latencyMeanMs
) {
}
