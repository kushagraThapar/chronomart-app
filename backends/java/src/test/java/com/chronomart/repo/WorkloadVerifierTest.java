package com.chronomart.repo;

import com.chronomart.web.dto.AnomalySummary;
import com.chronomart.web.dto.WorkloadAnomaly;
import com.chronomart.web.dto.WorkloadVerification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the oracle's L0 (self-consistency) and L1 (temporal, reference-model) checks
 * ({@link WorkloadVerifier} + {@link WorkloadVerificationState}). Each test scripts the reference
 * model (settled vs concurrent writes, session floors) and feeds a hand-built response, then
 * asserts the right anomaly — or none — under a given consistency level.
 *
 * <p>The "settled write" machinery is what keeps temporal checks free of false positives when two
 * writes race the same key (allocation order != commit order); several tests pin that down.
 */
class WorkloadVerifierTest {

    private static final String C = "Products";

    private final WorkloadVerifier verifier = new WorkloadVerifier();

    private WorkloadVerificationState state(String level) {
        WorkloadVerification cfg = new WorkloadVerification(
            true, level, null, new WorkloadVerification.Keyspace("wl", 100), 1.0, null, null, null);
        return new WorkloadVerificationState("run-test", cfg);
    }

    /** A pristine self-verifying doc for {@code key} at {@code seq} (does not touch the model). */
    private Map<String, Object> docAtSeq(WorkloadVerificationState s, String key, long seq) {
        return VerifiedValue.build(s.runId(), C, key, "sellerId", s.pkValueFor(key), 1, seq, null);
    }

    /** {@code n} sequential SOLE writes (each begins after the prior acked) — all settle; settledSeq=n. */
    private void writeSettled(WorkloadVerificationState s, String key, int n) {
        for (int i = 1; i <= n; i++) {
            long seq = s.beginWrite(C, key);
            s.ackWrite(C, key, seq);
        }
    }

    // ----- settled-write mechanics -----

    @Test
    void soleWriteSettles() {
        WorkloadVerificationState s = state("strong");
        long seq = s.beginWrite(C, "k");
        assertThat(s.ackWrite(C, "k", seq)).isTrue();
        assertThat(s.settledSeq(C, "k")).isEqualTo(1L);
    }

    @Test
    void concurrentWritesDoNotSettle() {
        WorkloadVerificationState s = state("strong");
        long a = s.beginWrite(C, "k");   // seq 1
        long b = s.beginWrite(C, "k");   // seq 2 begins while 1 still in-flight -> both concurrent
        assertThat(s.ackWrite(C, "k", a)).isFalse();
        assertThat(s.ackWrite(C, "k", b)).isFalse();
        assertThat(s.settledSeq(C, "k")).isZero();        // neither is an unambiguous latest
        assertThat(s.latestAckedSeq(C, "k")).isEqualTo(2L); // but both are acknowledged durable
    }

    // ----- L0: self-consistency -----

    @Test
    void cleanReadRaisesNothing() {
        WorkloadVerificationState s = state("session");
        writeSettled(s, "wl-000001", 1);
        verifier.verifyRead(s, 0, "pointRead", C, "wl-000001", docAtSeq(s, "wl-000001", 1), 1);
        assertThat(s.anomalyCount()).isZero();
    }

    @Test
    void wrongIdIsAnError() {
        WorkloadVerificationState s = state("session");
        Map<String, Object> doc = docAtSeq(s, "wl-000002", 1);   // valid doc for a DIFFERENT key
        verifier.verifyRead(s, 0, "pointRead", C, "wl-000001", doc, 0);
        assertThat(s.anomalies(0, 10)).singleElement().satisfies(x -> {
            assertThat(x.code()).isEqualTo("WRONG_ID");
            assertThat(x.severity()).isEqualTo(WorkloadAnomaly.SEVERITY_ERROR);
            assertThat(x.key()).isEqualTo("wl-000001");
        });
    }

    @Test
    void corruptedValueIsAnError() {
        WorkloadVerificationState s = state("session");
        writeSettled(s, "wl-000003", 1);
        Map<String, Object> doc = docAtSeq(s, "wl-000003", 1);
        doc.put(VerifiedValue.AMOUNT_FIELD, -1L);   // tamper a checksum-bound field
        verifier.verifyRead(s, 0, "pointRead", C, "wl-000003", doc, 1);
        assertThat(s.anomalies(0, 10)).anySatisfy(x -> assertThat(x.code()).isEqualTo("FIELD_MISMATCH"));
    }

    // ----- L1: not-found / lost write -----

    @Test
    void notFoundOnNeverWrittenKeyIsClean() {
        WorkloadVerificationState s = state("strong");
        verifier.verifyRead(s, 0, "pointRead", C, "wl-000009", null, 0);
        assertThat(s.anomalyCount()).isZero();   // legitimately absent — nothing was ever acked
    }

    @Test
    void lostWriteUnderStrongIsAnError() {
        WorkloadVerificationState s = state("strong");
        writeSettled(s, "wl-000010", 1);          // a durable write exists
        verifier.verifyRead(s, 0, "pointRead", C, "wl-000010", null, 1);   // ...but the read 404s
        assertThat(s.anomalies(0, 10)).singleElement().satisfies(x -> {
            assertThat(x.code()).isEqualTo("LOST_WRITE");
            assertThat(x.severity()).isEqualTo(WorkloadAnomaly.SEVERITY_ERROR);
            assertThat(x.expectedSeq()).isEqualTo(1L);
        });
    }

    @Test
    void lostWriteUnderSessionRequiresThisUserToHaveTouchedTheKey() {
        WorkloadVerificationState s = state("session");
        writeSettled(s, "wl-000011", 1);          // some user wrote it...
        // user 5 never touched it -> a 404 is tolerated (another user's write may not be visible).
        verifier.verifyRead(s, 5, "pointRead", C, "wl-000011", null, 1);
        AnomalySummary sum = s.summary(10);
        assertThat(sum.warnCount()).isEqualTo(1);
        assertThat(sum.errorCount()).isZero();
        assertThat(sum.byCode()).containsEntry("UNEXPECTED_NOT_FOUND", 1L);
    }

    @Test
    void lostWriteUnderSessionForOwnWriteIsAnError() {
        WorkloadVerificationState s = state("session");
        writeSettled(s, "wl-000012", 1);
        s.bumpSessionFloor(3, C, "wl-000012", 1, WorkloadVerificationState.SOURCE_WRITE);  // user 3 wrote it
        verifier.verifyRead(s, 3, "pointRead", C, "wl-000012", null, 1);
        assertThat(s.anomalies(0, 10)).singleElement().satisfies(x ->
            assertThat(x.code()).isEqualTo("LOST_WRITE"));
    }

    @Test
    void notFoundUnderEventualIsTolerated() {
        WorkloadVerificationState s = state("eventual");
        writeSettled(s, "wl-000013", 1);
        verifier.verifyRead(s, 0, "pointRead", C, "wl-000013", null, 1);
        assertThat(s.summary(10).warnCount()).isEqualTo(1);
        assertThat(s.summary(10).errorCount()).isZero();
    }

    // ----- L1: phantom -----

    @Test
    void phantomReadIsAnError() {
        WorkloadVerificationState s = state("session");
        // No writes -> maxAllocatedSeq is 0; a value claiming seq 5 was never issued for this key.
        verifier.verifyRead(s, 0, "pointRead", C, "wl-000014", docAtSeq(s, "wl-000014", 5), 0);
        assertThat(s.anomalies(0, 10)).singleElement().satisfies(x ->
            assertThat(x.code()).isEqualTo("PHANTOM_READ"));
    }

    // ----- L1: strong staleness (and its false-positive guard) -----

    @Test
    void staleReadUnderStrongIsAnError() {
        WorkloadVerificationState s = state("strong");
        writeSettled(s, "wl-000015", 3);          // settledSeq = 3
        // read returns an older value (seq 2) although settled seq 3 committed before the read began.
        verifier.verifyRead(s, 0, "pointRead", C, "wl-000015", docAtSeq(s, "wl-000015", 2), 3);
        assertThat(s.anomalies(0, 10)).singleElement().satisfies(x -> {
            assertThat(x.code()).isEqualTo("STALE_READ");
            assertThat(x.observedSeq()).isEqualTo(2L);
            assertThat(x.expectedSeq()).isEqualTo(3L);
        });
    }

    @Test
    void strongReadAtFloorIsClean() {
        WorkloadVerificationState s = state("strong");
        writeSettled(s, "wl-000016", 3);
        verifier.verifyRead(s, 0, "pointRead", C, "wl-000016", docAtSeq(s, "wl-000016", 3), 3);
        assertThat(s.anomalyCount()).isZero();
    }

    @Test
    void concurrentWritesDoNotFalseFlagStaleUnderStrong() {
        WorkloadVerificationState s = state("strong");
        writeSettled(s, "wl-000099", 1);          // settled baseline: seq 1
        long a = s.beginWrite(C, "wl-000099");    // seq 2 \
        long b = s.beginWrite(C, "wl-000099");    // seq 3  > concurrent -> neither settles
        s.ackWrite(C, "wl-000099", a);
        s.ackWrite(C, "wl-000099", b);
        // A read returns seq 2 (a's value committed last); settled floor is still 1, so 2 >= 1: clean.
        // Without the settled concept this would have false-flagged STALE (2 < latestAcked 3).
        verifier.verifyRead(s, 0, "pointRead", C, "wl-000099", docAtSeq(s, "wl-000099", 2), s.settledSeq(C, "wl-000099"));
        assertThat(s.anomalyCount()).isZero();
    }

    @Test
    void staleReadUnderEventualIsTolerated() {
        WorkloadVerificationState s = state("eventual");
        writeSettled(s, "wl-000017", 3);
        verifier.verifyRead(s, 0, "pointRead", C, "wl-000017", docAtSeq(s, "wl-000017", 1), 3);
        assertThat(s.anomalyCount()).isZero();    // staleness allowed under eventual
    }

    // ----- L1: session read-your-writes + monotonic reads -----

    @Test
    void readYourWriteViolationUnderSession() {
        WorkloadVerificationState s = state("session");
        writeSettled(s, "wl-000018", 5);
        s.bumpSessionFloor(7, C, "wl-000018", 5, WorkloadVerificationState.SOURCE_WRITE);  // user 7 wrote seq 5
        verifier.verifyRead(s, 7, "pointRead", C, "wl-000018", docAtSeq(s, "wl-000018", 4), 0); // reads older
        assertThat(s.anomalies(0, 10)).singleElement().satisfies(x -> {
            assertThat(x.code()).isEqualTo("READ_YOUR_WRITE_VIOLATION");
            assertThat(x.expectedSeq()).isEqualTo(5L);
        });
    }

    @Test
    void monotonicReadViolationUnderSession() {
        WorkloadVerificationState s = state("session");
        writeSettled(s, "wl-000019", 5);          // settledSeq = 5, so reads can establish a floor
        // user 2 first reads seq 5 (sets its read floor), then a later read returns seq 3.
        verifier.verifyRead(s, 2, "pointRead", C, "wl-000019", docAtSeq(s, "wl-000019", 5), 5);
        verifier.verifyRead(s, 2, "pointRead", C, "wl-000019", docAtSeq(s, "wl-000019", 3), 5);
        assertThat(s.anomalies(0, 10)).singleElement().satisfies(x ->
            assertThat(x.code()).isEqualTo("MONOTONIC_READ_VIOLATION"));
    }

    @Test
    void sessionFloorIsPerUserNotGlobal() {
        WorkloadVerificationState s = state("session");
        writeSettled(s, "wl-000020", 5);
        s.bumpSessionFloor(1, C, "wl-000020", 5, WorkloadVerificationState.SOURCE_READ);  // user 1 saw seq 5
        // a DIFFERENT user (2) reading seq 2 is fine — session is per-session, not global.
        verifier.verifyRead(s, 2, "pointRead", C, "wl-000020", docAtSeq(s, "wl-000020", 2), 5);
        assertThat(s.anomalyCount()).isZero();
    }

    @Test
    void nonSessionLevelsDoNotTrackPerUserFloor() {
        WorkloadVerificationState s = state("strong");
        s.bumpSessionFloor(0, C, "wl-000021", 9, WorkloadVerificationState.SOURCE_READ);
        assertThat(s.sessionFloorSeq(0, C, "wl-000021")).isZero();  // tracking is gated on level=session
    }

    // ----- L0: query + vector (verifier methods exist; executor wiring lands separately) -----

    @Test
    void scopedQueryFlagsStrippedEnvelope() {
        WorkloadVerificationState s = state("session");
        Map<String, Object> good = verifier.buildWriteDoc(s, C, "wl-000026", "sellerId", s.pkValueFor("wl-000026"), 1, null);
        Object pk = good.get("sellerId");
        // a doc on the right partition but with no _verify envelope (stripped / foreign write)
        Map<String, Object> stripped = new java.util.LinkedHashMap<>();
        stripped.put("id", "wl-000027");
        stripped.put("sellerId", pk);
        verifier.verifyScopedQuery(s, "query", C, "sellerId", pk, List.of(good, stripped));
        assertThat(s.anomalies(0, 10)).singleElement().satisfies(x -> {
            assertThat(x.code()).isEqualTo("CHECKSUM_MISMATCH");
            assertThat(x.key()).isEqualTo("wl-000027");
        });
    }

    @Test
    void scopedQueryFlagsCrossPartitionLeak() {
        WorkloadVerificationState s = state("session");
        Map<String, Object> good = verifier.buildWriteDoc(s, C, "wl-000022", "sellerId", s.pkValueFor("wl-000022"), 1, null);
        Map<String, Object> leaked = verifier.buildWriteDoc(s, C, "wl-000023", "sellerId", s.pkValueFor("wl-000023"), 1, null);
        leaked.put("sellerId", "some-other-partition");
        verifier.verifyScopedQuery(s, "query", C, "sellerId", good.get("sellerId"), List.of(good, leaked));
        assertThat(s.anomalies(0, 10)).anySatisfy(x -> assertThat(x.code()).isEqualTo("PREDICATE_VIOLATION"));
    }

    @Test
    void distanceMetricOrderViolationIsFlagged() {
        WorkloadVerificationState s = state("session");
        // ascending=distance: a smaller distance after a larger one breaks most-similar-first.
        verifier.verifyVectorOrder(s, "vectorSearch", "ProductVectors", List.of(0.1, 0.2, 0.15), false);
        assertThat(s.anomalies(0, 10)).singleElement().satisfies(x ->
            assertThat(x.code()).isEqualTo("VECTOR_ORDER_VIOLATION"));
    }

    @Test
    void monotonicDistancePageRaisesNothing() {
        WorkloadVerificationState s = state("session");
        verifier.verifyVectorOrder(s, "vectorSearch", "ProductVectors", List.of(0.1, 0.2, 0.2, 0.9), false);
        assertThat(s.anomalyCount()).isZero();
    }

    @Test
    void cosineDescendingOrderIsClean() {
        WorkloadVerificationState s = state("session");
        // COSINE similarity descends, most-similar-first (1.0 -> 0.0); this must NOT flag.
        verifier.verifyVectorOrder(s, "vectorSearch", "ProductVectors", List.of(0.95, 0.80, 0.80, 0.10), true);
        assertThat(s.anomalyCount()).isZero();
    }

    @Test
    void cosineSimilarityRisingIsViolation() {
        WorkloadVerificationState s = state("session");
        // similarity that increases down the page means the ranking is broken.
        verifier.verifyVectorOrder(s, "vectorSearch", "ProductVectors", List.of(0.90, 0.95), true);
        assertThat(s.anomalies(0, 10)).singleElement().satisfies(x ->
            assertThat(x.code()).isEqualTo("VECTOR_ORDER_VIOLATION"));
    }

    @Test
    void sequencesAreMonotonicPerKey() {
        WorkloadVerificationState s = state("session");
        long first = s.beginWrite(C, "wl-000024");
        long second = s.beginWrite(C, "wl-000024");
        long otherKey = s.beginWrite(C, "wl-000025");
        assertThat(second).isGreaterThan(first);
        assertThat(otherKey).isEqualTo(1L);   // per-key counter, independent of other keys
    }

    @Test
    void summaryIsNullWhenNoAnomalies() {
        assertThat(state("session").summary(10)).isNull();
    }
}
