package com.chronomart.repo;

import com.chronomart.web.dto.WorkloadAnomaly;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The L0 (response self-consistency) layer of the workload correctness oracle. Stateless logic
 * that, given an op's response and the per-run {@link WorkloadVerificationState}, detects
 * anomalies that need <em>no</em> cross-op history:
 *
 * <ul>
 *   <li><b>WRONG_ID</b> — a point read returned a document whose {@code id} is not the key we asked
 *       for (the SDK / server routed or matched the wrong item).</li>
 *   <li><b>CHECKSUM_MISMATCH / FIELD_MISMATCH</b> — a returned self-verifying value is internally
 *       inconsistent (torn, partial, forged, or cross-container).</li>
 *   <li><b>PREDICATE_VIOLATION</b> — a query returned a document that does not satisfy the
 *       partition-scoped predicate (cross-partition leakage / wrong filter).</li>
 *   <li><b>VECTOR_ORDER_VIOLATION</b> — vector-search results are not in non-decreasing distance
 *       order (the index path returned an unsorted page).</li>
 *   <li><b>UNEXPECTED_NOT_FOUND</b> — a keyspace key that the run pre-seeded read back as 404
 *       (recorded as a WARN here; the reference-model slice promotes a provably-lost write to an
 *       ERROR).</li>
 * </ul>
 *
 * <p>Temporal correctness (read-your-writes, staleness, lost/phantom writes) is intentionally out
 * of scope for this layer — it needs the reference model and is added next. Here we trust each
 * document's own claimed {@code seq} and assert only what a single response can prove.
 */
@Component
public class WorkloadVerifier {

    /**
     * Build the self-verifying document for a write to {@code key}. Allocates the next sequence for
     * {@code (container,key)} from the run state, so concurrent writers to the same key get
     * strictly increasing seqs.
     */
    public Map<String, Object> buildWriteDoc(WorkloadVerificationState state, String container,
                                             String key, String pkField, Object pkValue,
                                             int writer, Map<String, Object> template) {
        long seq = state.beginWrite(container, key);
        return VerifiedValue.build(state.runId(), container, key, pkField, pkValue, writer, seq, template);
    }

    /**
     * Verify a point-read response, both L0 (self-consistency, wrong-id) and L1 (temporal: phantom,
     * stale, read-your-writes / monotonic, lost write), parameterized by the run's consistency
     * {@code level}. {@code observedDoc} is {@code null} on 404. {@code settledFloorAtStart} is the
     * key's {@link WorkloadVerificationState#settledSeq} captured by the caller <em>before</em> the
     * read began — the highest unambiguously-ordered (non-concurrent) committed value at that point,
     * which a linearizable (strong) read must not fall below. Using the <em>settled</em> seq rather
     * than the max acked seq is what keeps the check free of false positives when two writes race the
     * same key (allocation order != commit order). Unused for non-strong levels.
     */
    public void verifyRead(WorkloadVerificationState state, int userIdx, String op, String container,
                           String key, Map<String, Object> observedDoc, long settledFloorAtStart) {
        long opSeq = state.nextOpSeq();
        String level = state.level();
        if (observedDoc == null) {
            verifyNotFound(state, userIdx, op, container, key, level, opSeq);
            return;
        }
        Object actualId = observedDoc.get("id");
        if (!key.equals(actualId)) {
            state.record(anomaly("WRONG_ID", WorkloadAnomaly.SEVERITY_ERROR, op, container, key,
                "read key '" + key + "' but document id is '" + actualId + "'",
                opSeq, VerifiedValue.seqOf(observedDoc), null));
            return; // a wrong-doc makes its internal + temporal checks meaningless
        }
        recordValueViolations(state, op, container, key, observedDoc, opSeq);
        Long observedSeq = VerifiedValue.seqOf(observedDoc);
        if (observedSeq != null) {
            verifyTemporal(state, userIdx, op, container, key, observedSeq, level, settledFloorAtStart, opSeq);
            // A read advances this user's monotonic-read floor only with a *settled* value (one with
            // an unambiguous order); otherwise a concurrent-write race could plant a false floor.
            if (observedSeq <= state.settledSeq(container, key)) {
                state.bumpSessionFloor(userIdx, container, key, observedSeq, WorkloadVerificationState.SOURCE_READ);
            }
        }
    }

    /** A 404: an anomaly only when a durable write is known to exist (level-dependent). */
    private void verifyNotFound(WorkloadVerificationState state, int userIdx, String op, String container,
                                String key, String level, long opSeq) {
        long acked = state.latestAckedSeq(container, key);
        if (acked <= 0 || state.tombstoned(container, key)) {
            return; // legitimately absent — never durably written (or deleted)
        }
        boolean thisUserTouched = state.sessionFloorSeq(userIdx, container, key) > 0;
        if ("strong".equals(level) || ("session".equals(level) && thisUserTouched)) {
            // Strong: a linearizable read must see the latest acked write. Session: read-your-writes
            // — this user wrote/read the key, so its disappearance is a lost write.
            state.record(anomaly("LOST_WRITE", WorkloadAnomaly.SEVERITY_ERROR, op, container, key,
                "read 404 but seq " + acked + " was acknowledged durable for this key",
                opSeq, null, acked));
        } else {
            // session-but-another-user's-write, or bounded/eventual: tolerated (window enforcement deferred).
            state.record(anomaly("UNEXPECTED_NOT_FOUND", WorkloadAnomaly.SEVERITY_WARN, op, container, key,
                "read 404 for a key with an acknowledged write (seq " + acked + ") — allowed under " + level,
                opSeq, null, acked));
        }
    }

    /** L1 temporal checks against the reference model + this user's session floor. */
    private void verifyTemporal(WorkloadVerificationState state, int userIdx, String op, String container,
                                String key, long observedSeq, String level, long settledFloorAtStart, long opSeq) {
        long maxAlloc = state.maxAllocatedSeq(container, key);
        if (observedSeq > maxAlloc) {
            // A self-consistent value whose seq was never allocated for this key: fabricated / duplicated.
            state.record(anomaly("PHANTOM_READ", WorkloadAnomaly.SEVERITY_ERROR, op, container, key,
                "observed seq " + observedSeq + " exceeds the max allocated seq " + maxAlloc + " for this key",
                opSeq, observedSeq, null));
            return;
        }
        switch (level) {
            case "strong" -> {
                // Compare against the SETTLED floor: a value committed unambiguously (no concurrent
                // writer) before this read began. Reading older than that is a real linearizability
                // violation; concurrent-write races never advance the settled floor, so no false alarm.
                if (observedSeq < settledFloorAtStart) {
                    state.record(anomaly("STALE_READ", WorkloadAnomaly.SEVERITY_ERROR, op, container, key,
                        "strong read returned seq " + observedSeq + " but settled seq " + settledFloorAtStart
                            + " was already committed before the read began",
                        opSeq, observedSeq, settledFloorAtStart));
                }
            }
            case "session" -> {
                long floor = state.sessionFloorSeq(userIdx, container, key);
                if (observedSeq < floor) {
                    boolean fromWrite = state.sessionFloorSource(userIdx, container, key)
                        == WorkloadVerificationState.SOURCE_WRITE;
                    String code = fromWrite ? "READ_YOUR_WRITE_VIOLATION" : "MONOTONIC_READ_VIOLATION";
                    state.record(anomaly(code, WorkloadAnomaly.SEVERITY_ERROR, op, container, key,
                        "session read returned seq " + observedSeq + " < this user's floor " + floor
                            + (fromWrite ? " (its own acknowledged write)" : " (a value it previously read)"),
                        opSeq, observedSeq, floor));
                }
            }
            default -> {
                // bounded / eventual: staleness tolerated; only phantom (checked above) is a hard error here.
            }
        }
    }

    /**
     * Verify a partition-scoped query response: every returned document must carry the scoped
     * partition-key value, and each self-verifying value must be internally consistent.
     */
    public void verifyScopedQuery(WorkloadVerificationState state, String op, String container,
                                  String pkField, Object pkValue, List<Map<String, Object>> docs) {
        long opSeq = state.nextOpSeq();
        for (Map<String, Object> doc : docs) {
            Object actualPk = doc.get(pkField);
            if (actualPk == null || !actualPk.equals(pkValue)) {
                String key = String.valueOf(doc.get("id"));
                state.record(anomaly("PREDICATE_VIOLATION", WorkloadAnomaly.SEVERITY_ERROR, op, container,
                    key, "query scoped to " + pkField + "='" + pkValue + "' returned a doc with "
                        + pkField + "='" + actualPk + "' (cross-partition leak)",
                    opSeq, VerifiedValue.seqOf(doc), null));
                continue;
            }
            if (VerifiedValue.isVerified(doc)) {
                recordValueViolations(state, op, container, String.valueOf(doc.get("id")), doc, opSeq);
            }
        }
    }

    /**
     * Verify that vector-search scores are non-decreasing (Cosmos returns most-similar-first, i.e.
     * smallest distance first). {@code scores} is the ordered list of per-match distances.
     */
    public void verifyVectorOrder(WorkloadVerificationState state, String op, String container,
                                  List<Double> scores) {
        long opSeq = state.nextOpSeq();
        for (int i = 1; i < scores.size(); i++) {
            Double prev = scores.get(i - 1);
            Double cur = scores.get(i);
            if (prev != null && cur != null && cur < prev) {
                state.record(anomaly("VECTOR_ORDER_VIOLATION", WorkloadAnomaly.SEVERITY_ERROR, op, container,
                    null, "result " + i + " distance " + cur + " < previous " + prev
                        + " (results not in non-decreasing distance order)",
                    opSeq, null, null));
                return; // one report per page is enough
            }
        }
    }

    private void recordValueViolations(WorkloadVerificationState state, String op, String container,
                                       String key, Map<String, Object> doc, long opSeq) {
        Long seq = VerifiedValue.seqOf(doc);
        for (VerifiedValue.Violation v : VerifiedValue.validate(container, doc)) {
            state.record(anomaly(v.code(), WorkloadAnomaly.SEVERITY_ERROR, op, container, key,
                v.detail(), opSeq, seq, null));
        }
    }

    private static WorkloadAnomaly anomaly(String code, String severity, String op, String container,
                                           String key, String detail, long opSeq,
                                           Long observedSeq, Long expectedSeq) {
        return new WorkloadAnomaly(code, severity, op, container, key, detail, opSeq,
            observedSeq, expectedSeq, System.currentTimeMillis());
    }
}
