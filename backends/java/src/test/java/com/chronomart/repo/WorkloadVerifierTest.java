package com.chronomart.repo;

import com.chronomart.web.dto.AnomalySummary;
import com.chronomart.web.dto.WorkloadAnomaly;
import com.chronomart.web.dto.WorkloadVerification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the L0 layer of the oracle ({@link WorkloadVerifier} + {@link
 * WorkloadVerificationState}). Each test drives a single check by feeding a hand-built response
 * (a pristine value, a wrong-id read, a corrupted value, a 404, a cross-partition query row, an
 * out-of-order vector page) and asserting the right anomaly — or none — lands in the state.
 */
class WorkloadVerifierTest {

    private final WorkloadVerifier verifier = new WorkloadVerifier();

    private WorkloadVerificationState newState() {
        WorkloadVerification cfg = new WorkloadVerification(
            true, "session", null, new WorkloadVerification.Keyspace("wl", 100), 1.0, null, null, null);
        return new WorkloadVerificationState("run-test", cfg);
    }

    /** Build a pristine self-verifying doc the way the engine's write path would. */
    private Map<String, Object> goodDoc(WorkloadVerificationState s, String key) {
        return verifier.buildWriteDoc(s, "Products", key, "sellerId", s.pkValueFor(key), 1, null);
    }

    @Test
    void cleanReadRaisesNothing() {
        WorkloadVerificationState s = newState();
        Map<String, Object> doc = goodDoc(s, "wl-000001");
        verifier.verifyPointRead(s, "pointRead", "Products", "wl-000001", doc);
        assertThat(s.anomalyCount()).isZero();
    }

    @Test
    void wrongIdIsAnError() {
        WorkloadVerificationState s = newState();
        Map<String, Object> doc = goodDoc(s, "wl-000002");   // a valid doc for a DIFFERENT key
        verifier.verifyPointRead(s, "pointRead", "Products", "wl-000001", doc);
        List<WorkloadAnomaly> a = s.anomalies(0, 10);
        assertThat(a).singleElement().satisfies(x -> {
            assertThat(x.code()).isEqualTo("WRONG_ID");
            assertThat(x.severity()).isEqualTo(WorkloadAnomaly.SEVERITY_ERROR);
            assertThat(x.key()).isEqualTo("wl-000001");
        });
    }

    @Test
    void corruptedValueIsAnError() {
        WorkloadVerificationState s = newState();
        Map<String, Object> doc = goodDoc(s, "wl-000003");
        doc.put(VerifiedValue.AMOUNT_FIELD, -1L);   // tamper a checksum-bound field
        verifier.verifyPointRead(s, "pointRead", "Products", "wl-000003", doc);
        assertThat(s.anomalies(0, 10)).anySatisfy(x ->
            assertThat(x.code()).isEqualTo("FIELD_MISMATCH"));
    }

    @Test
    void notFoundIsAWarn() {
        WorkloadVerificationState s = newState();
        verifier.verifyPointRead(s, "pointRead", "Products", "wl-000009", null);
        AnomalySummary sum = s.summary(10);
        assertThat(sum.total()).isEqualTo(1);
        assertThat(sum.warnCount()).isEqualTo(1);
        assertThat(sum.errorCount()).isZero();
        assertThat(sum.byCode()).containsEntry("UNEXPECTED_NOT_FOUND", 1L);
    }

    @Test
    void scopedQueryFlagsCrossPartitionLeak() {
        WorkloadVerificationState s = newState();
        String key = "wl-000004";
        Map<String, Object> good = goodDoc(s, key);
        // A row whose sellerId does not match the scoped pk we queried for.
        Map<String, Object> leaked = goodDoc(s, "wl-000005");
        leaked.put("sellerId", "some-other-partition");
        verifier.verifyScopedQuery(s, "query", "Products", "sellerId", good.get("sellerId"),
            List.of(good, leaked));
        assertThat(s.anomalies(0, 10)).anySatisfy(x ->
            assertThat(x.code()).isEqualTo("PREDICATE_VIOLATION"));
    }

    @Test
    void vectorOrderViolationIsFlagged() {
        WorkloadVerificationState s = newState();
        verifier.verifyVectorOrder(s, "vectorSearch", "ProductVectors", List.of(0.1, 0.2, 0.15));
        assertThat(s.anomalies(0, 10)).singleElement().satisfies(x ->
            assertThat(x.code()).isEqualTo("VECTOR_ORDER_VIOLATION"));
    }

    @Test
    void monotonicVectorPageRaisesNothing() {
        WorkloadVerificationState s = newState();
        verifier.verifyVectorOrder(s, "vectorSearch", "ProductVectors", List.of(0.1, 0.2, 0.2, 0.9));
        assertThat(s.anomalyCount()).isZero();
    }

    @Test
    void sequencesAreMonotonicPerKey() {
        WorkloadVerificationState s = newState();
        long first = VerifiedValue.seqOf(goodDoc(s, "wl-000006"));
        long second = VerifiedValue.seqOf(goodDoc(s, "wl-000006"));
        long otherKey = VerifiedValue.seqOf(goodDoc(s, "wl-000007"));
        assertThat(second).isGreaterThan(first);
        assertThat(otherKey).isEqualTo(1L);   // per-key counter, independent of other keys
    }

    @Test
    void summaryIsNullWhenNoAnomalies() {
        assertThat(newState().summary(10)).isNull();
    }
}
