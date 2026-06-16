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
        long seq = state.nextSeq(container, key);
        return VerifiedValue.build(state.runId(), container, key, pkField, pkValue, writer, seq, template);
    }

    /**
     * Verify a point-read response. {@code observedDoc} is {@code null} when the read returned 404.
     * Returns nothing; any anomalies are recorded into {@code state}.
     */
    public void verifyPointRead(WorkloadVerificationState state, String op, String container,
                                String requestedKey, Map<String, Object> observedDoc) {
        long opSeq = state.nextOpSeq();
        if (observedDoc == null) {
            // Pre-seed wrote every key, so a 404 is suspicious — but proving it is a *lost* write
            // needs the reference model. Surface as WARN for now.
            state.record(anomaly("UNEXPECTED_NOT_FOUND", WorkloadAnomaly.SEVERITY_WARN, op, container,
                requestedKey, "point read returned 404 for a pre-seeded keyspace key", opSeq, null, null));
            return;
        }
        Object actualId = observedDoc.get("id");
        if (!requestedKey.equals(actualId)) {
            state.record(anomaly("WRONG_ID", WorkloadAnomaly.SEVERITY_ERROR, op, container, requestedKey,
                "read key '" + requestedKey + "' but document id is '" + actualId + "'",
                opSeq, VerifiedValue.seqOf(observedDoc), null));
            return; // a wrong-doc makes its internal checks meaningless
        }
        recordValueViolations(state, op, container, requestedKey, observedDoc, opSeq);
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
