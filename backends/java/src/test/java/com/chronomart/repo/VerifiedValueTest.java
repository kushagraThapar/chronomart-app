package com.chronomart.repo;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link VerifiedValue} self-verifying value scheme — the foundation of the
 * workload correctness oracle. These run fully offline (no Cosmos, no Spring): build a document,
 * then prove that {@link VerifiedValue#validate} accepts a pristine value and flags every class
 * of single-field tamper.
 */
class VerifiedValueTest {

    private static final String RUN = "run-test";
    private static final String CONTAINER = "Products";

    @Test
    void buildThenValidateRoundTripsClean() {
        Map<String, Object> doc = VerifiedValue.build(
            RUN, CONTAINER, "wl-000042", "sellerId", "wl-pk-7", 3, 128, null);

        assertThat(doc.get("id")).isEqualTo("wl-000042");
        assertThat(doc.get("sellerId")).isEqualTo("wl-pk-7");
        assertThat(VerifiedValue.seqOf(doc)).isEqualTo(128L);
        assertThat(VerifiedValue.isVerified(doc)).isTrue();
        assertThat(VerifiedValue.validate(CONTAINER, doc)).isEmpty();
    }

    @Test
    void derivedFieldsAreDeterministic() {
        Map<String, Object> a = VerifiedValue.build(RUN, CONTAINER, "wl-1", "pk", "v", 0, 5, null);
        Map<String, Object> b = VerifiedValue.build(RUN, CONTAINER, "wl-1", "pk", "v", 1, 5, null);
        // Same (key, seq) -> identical business fields regardless of writer; only writtenAtNanos differs.
        assertThat(a.get(VerifiedValue.NAME_FIELD)).isEqualTo(b.get(VerifiedValue.NAME_FIELD));
        assertThat(a.get(VerifiedValue.AMOUNT_FIELD)).isEqualTo(b.get(VerifiedValue.AMOUNT_FIELD));
        assertThat(a.get(VerifiedValue.NAME_FIELD)).isEqualTo("wl/wl-1#5");
    }

    @Test
    void templateFieldsAreCarriedButCannotOverridePkOrId() {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("brand", "Acme");
        template.put("sellerId", "ATTACKER");   // must not win over the real pk
        template.put("id", "ATTACKER");          // must not win over the key
        Map<String, Object> doc = VerifiedValue.build(
            RUN, CONTAINER, "wl-9", "sellerId", "wl-pk-2", 1, 10, template);

        assertThat(doc.get("brand")).isEqualTo("Acme");
        assertThat(doc.get("sellerId")).isEqualTo("wl-pk-2");
        assertThat(doc.get("id")).isEqualTo("wl-9");
        assertThat(VerifiedValue.validate(CONTAINER, doc)).isEmpty();
    }

    @Test
    void detectsMissingEnvelope() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", "wl-1");
        List<VerifiedValue.Violation> v = VerifiedValue.validate(CONTAINER, doc);
        assertThat(v).extracting(VerifiedValue.Violation::code).containsExactly("CHECKSUM_MISMATCH");
    }

    @Test
    void detectsTamperedBusinessField() {
        Map<String, Object> doc = VerifiedValue.build(RUN, CONTAINER, "wl-5", "pk", "v", 1, 7, null);
        doc.put(VerifiedValue.AMOUNT_FIELD, 999_999L);   // corrupt one field, leave checksum
        // The checksum binds the *derived* (expected) fields, so a payload tamper surfaces as a
        // FIELD_MISMATCH (the recomputed checksum still matches the unchanged seq/identity).
        List<VerifiedValue.Violation> v = VerifiedValue.validate(CONTAINER, doc);
        assertThat(v).extracting(VerifiedValue.Violation::code).containsExactly("FIELD_MISMATCH");
    }

    @Test
    void detectsForgedChecksum() {
        Map<String, Object> doc = VerifiedValue.build(RUN, CONTAINER, "wl-5", "pk", "v", 1, 7, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) doc.get(VerifiedValue.VERIFY_FIELD);
        verify.put("checksum", "deadbeefdeadbeef");
        List<VerifiedValue.Violation> v = VerifiedValue.validate(CONTAINER, doc);
        assertThat(v).extracting(VerifiedValue.Violation::code).containsExactly("CHECKSUM_MISMATCH");
    }

    @Test
    void detectsCrossContainerReplay() {
        // A value built for Products, read back from a different container, must fail: the
        // checksum binds the container name.
        Map<String, Object> doc = VerifiedValue.build(RUN, "Products", "wl-5", "pk", "v", 1, 7, null);
        assertThat(VerifiedValue.validate("Inventory", doc))
            .extracting(VerifiedValue.Violation::code).containsExactly("CHECKSUM_MISMATCH");
    }

    @Test
    void tamperedSeqIsCaught() {
        // Rewriting seq alone makes the derived fields wrong for the new seq AND breaks the
        // checksum — either way the value is rejected.
        Map<String, Object> doc = VerifiedValue.build(RUN, CONTAINER, "wl-5", "pk", "v", 1, 7, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) doc.get(VerifiedValue.VERIFY_FIELD);
        verify.put("seq", 8L);
        assertThat(VerifiedValue.validate(CONTAINER, doc)).isNotEmpty();
    }
}
