package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * Opt-in correctness-verification config for a workload run. Mirrors
 * {@code contracts/openapi.yaml#/components/schemas/WorkloadVerification}. Absent (or
 * {@code enabled=false}) means the run behaves exactly as a pure-performance run — the oracle
 * adds nothing to the hot path.
 *
 * <p>When enabled, the engine operates over an <b>owned, bounded keyspace</b> ({@link Keyspace})
 * so read and write steps collide on the same keys; every write embeds a self-verifying value
 * (see {@code VerifiedValue}) and every read/query/vector response is checked by the oracle.
 *
 * <p>Fields beyond what the first slice consumes ({@code level}, {@code stalenessWindowMs},
 * {@code historyCap}, {@code failThreshold}) are plumbed now so the contract is stable as the
 * reference-model, offline-analyzer and CI-gate slices land.
 *
 * @param enabled           master switch (default false)
 * @param level             consistency level to assert against:
 *                          {@code session|strong|bounded|eventual} (default {@code session})
 * @param stalenessWindowMs tolerance for {@code bounded}/{@code eventual} (default 5000)
 * @param keyspace          the owned keyspace reads/writes operate over (default {@code wl}/1000)
 * @param sampleRate        fraction of ops to verify live, 0..1 — a perf escape hatch (default 1.0)
 * @param historyCap        max op-history records retained for offline analysis (default 1_000_000)
 * @param failThreshold     CI gate: a run with more than this many ERROR anomalies fails (default 0)
 * @param seed              RNG seed so a run's op-mix + keys replay deterministically (nullable → random)
 */
@JsonInclude(Include.NON_NULL)
public record WorkloadVerification(
    Boolean enabled,

    @Pattern(regexp = "^(session|strong|bounded|eventual)$",
             message = "level must be one of session|strong|bounded|eventual")
    String level,

    @Min(0) Long stalenessWindowMs,

    @Valid Keyspace keyspace,

    @DecimalMin(value = "0.0", message = "sampleRate must be in [0,1]")
    @DecimalMax(value = "1.0", message = "sampleRate must be in [0,1]")
    Double sampleRate,

    @Min(1) Long historyCap,

    @Min(0) Long failThreshold,

    Long seed
) {

    public static final String DEFAULT_LEVEL = "session";
    public static final String DEFAULT_PREFIX = "wl";
    public static final int DEFAULT_KEYSPACE_SIZE = 1000;
    // keep in sync with WorkloadVerification.Keyspace.size @Max
    public static final int MAX_KEYSPACE_SIZE = 1_000_000;
    public static final long DEFAULT_STALENESS_MS = 5000;
    public static final long DEFAULT_HISTORY_CAP = 1_000_000;

    /**
     * The owned keyspace: keys are {@code prefix-000000 .. prefix-(size-1)} zero-padded. Reads
     * and writes draw from this fixed set so they collide, which is what makes read-your-write
     * and staleness checkable.
     */
    @JsonInclude(Include.NON_NULL)
    public record Keyspace(
        String prefix,
        @Min(1) @Max(MAX_KEYSPACE_SIZE) Integer size
    ) {
        public String prefixOrDefault() {
            return prefix == null || prefix.isBlank() ? DEFAULT_PREFIX : prefix;
        }

        public int sizeOrDefault() {
            return size == null ? DEFAULT_KEYSPACE_SIZE : size;
        }
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public String levelOrDefault() {
        return level == null || level.isBlank() ? DEFAULT_LEVEL : level;
    }

    public double sampleRateOrDefault() {
        return sampleRate == null ? 1.0 : sampleRate;
    }

    public long stalenessWindowMsOrDefault() {
        return stalenessWindowMs == null ? DEFAULT_STALENESS_MS : stalenessWindowMs;
    }

    public long historyCapOrDefault() {
        return historyCap == null ? DEFAULT_HISTORY_CAP : historyCap;
    }

    public long failThresholdOrDefault() {
        return failThreshold == null ? 0 : failThreshold;
    }

    public Keyspace keyspaceOrDefault() {
        return keyspace == null ? new Keyspace(DEFAULT_PREFIX, DEFAULT_KEYSPACE_SIZE) : keyspace;
    }
}
