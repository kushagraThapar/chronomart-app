package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Top-level spec for {@code POST /api/v1/workloads/run}. A workload is a weighted mix of
 * Cosmos operations executed by {@code concurrency} virtual users for {@code durationSeconds},
 * with optional {@code rampSeconds} for a linear ramp-up (PR2; ignored in v1).
 *
 * <p>The engine treats {@link WorkloadStep#weight()} as relative weights summed across steps;
 * the values do not need to sum to any specific total. A step with weight 0 is rejected.
 *
 * <p><b>Limits</b> (enforced here, not in the controller): durationSeconds 1..1800,
 * concurrency 1..{@link com.chronomart.repo.WorkloadEngine#MAX_CONCURRENCY},
 * up to 32 steps. The concurrency ceiling reflects what the local emulator can sustain
 * before RU throttling dominates; real Cosmos accounts can go higher and the cap should be
 * configurable later.
 *
 * <p><b>Note:</b> The {@code @Max(64)} on {@code concurrency} must be kept in sync with
 * {@link com.chronomart.repo.WorkloadEngine#MAX_CONCURRENCY}. Annotation values must be
 * compile-time constants, so they cannot reference the field directly.
 */
@JsonInclude(Include.NON_NULL)
public record WorkloadSpec(
    @NotBlank String name,
    @Min(1) @Max(1800) int durationSeconds,
    @Min(1) @Max(64) int concurrency,
    @Min(0) @Max(60) Integer rampSeconds,
    @NotEmpty @Valid List<WorkloadStep> steps,
    @Valid WorkloadVerification verification
) {
    /** Back-compat convenience for callers (and tests) that predate the verification block. */
    public WorkloadSpec(String name, int durationSeconds, int concurrency,
                        Integer rampSeconds, List<WorkloadStep> steps) {
        this(name, durationSeconds, concurrency, rampSeconds, steps, null);
    }
}
