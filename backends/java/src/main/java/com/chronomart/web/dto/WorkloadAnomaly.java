package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * A single correctness anomaly detected by the workload oracle while (or after) a run executes.
 * Mirrors {@code contracts/openapi.yaml#/components/schemas/WorkloadAnomaly}.
 *
 * <p>An anomaly is distinct from an <em>error</em>: an error is an SDK exception (counted in
 * {@code WorkloadOpStats.errorCount}); an anomaly is a 2xx response that is nevertheless
 * <em>wrong</em> (wrong document, stale read, corrupted value, predicate leak, broken domain
 * invariant). The whole point of the oracle is to surface this second class, which the old
 * engine reported as success.
 *
 * @param code         machine-readable category (e.g. {@code WRONG_ID}, {@code CHECKSUM_MISMATCH},
 *                     {@code STALE_READ}, {@code PREDICATE_VIOLATION}, {@code VECTOR_ORDER_VIOLATION})
 * @param severity     {@code ERROR} (a definite correctness bug) or {@code WARN} (suspicious but
 *                     allowed under the configured consistency level)
 * @param op           the workload op that produced it (e.g. {@code pointRead})
 * @param container    target container
 * @param key          the document key / id involved (nullable for whole-response anomalies)
 * @param detail       human-readable specifics
 * @param opSeqGlobal  monotonic per-run op index, so an anomaly can be located in the history
 * @param observedSeq  the {@code _verify.seq} actually read (nullable)
 * @param expectedSeq  the {@code _verify.seq} the oracle expected (nullable; set by later slices)
 * @param atEpochMillis wall-clock time the anomaly was recorded
 */
@JsonInclude(Include.NON_NULL)
public record WorkloadAnomaly(
    String code,
    String severity,
    String op,
    String container,
    String key,
    String detail,
    long opSeqGlobal,
    Long observedSeq,
    Long expectedSeq,
    long atEpochMillis
) {
    public static final String SEVERITY_ERROR = "ERROR";
    public static final String SEVERITY_WARN = "WARN";
}
