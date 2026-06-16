package com.chronomart.repo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The self-verifying value scheme that lets the workload oracle check correctness with
 * {@code O(distinct keys)} state instead of remembering every write.
 *
 * <p>Every write the engine makes in verification mode embeds a {@code _verify} envelope plus
 * a pair of business fields ({@code vName}, {@code vAmount}) whose values are a deterministic
 * function of {@code (key, seq)}. A reader can therefore validate any returned document on its
 * own — recompute the expected fields and the checksum from the document's own claimed
 * {@code seq}, and compare — without consulting a write log:
 *
 * <pre>{@code
 * {
 *   "id": "wl-000042",            // == the keyspace key
 *   "sellerId": "wl-pk-7",        // partition key (deterministic from key)
 *   "vName":  "wl/wl-000042#128", // = derivedName(key, seq)
 *   "vAmount": 906,               // = derivedAmount(key, seq)
 *   "_verify": { "runId", "writer", "seq": 128, "writtenAtNanos",
 *                "checksum": sha256(runId|container|key|writer|seq|vName|vAmount)[0..16] }
 * }
 * }</pre>
 *
 * <h2>What {@link #validate} catches (L0, intra-response, no history)</h2>
 * <ul>
 *   <li><b>CHECKSUM_MISMATCH</b> — a torn / partially-applied / forged write: the stored
 *       checksum does not equal the checksum recomputed from the document's own fields.</li>
 *   <li><b>FIELD_MISMATCH</b> — {@code vName}/{@code vAmount} do not match what
 *       {@code (id, seq)} should derive (a single field was corrupted or not written
 *       atomically with the rest).</li>
 * </ul>
 *
 * <p>The checksum binds {@code container}, {@code key (=id)}, {@code writer}, {@code seq} and
 * the two business fields, so any single-field tamper is detected even if the tamperer leaves
 * the rest intact. What this class does <em>not</em> decide is whether the document's
 * {@code seq} is <em>temporally</em> valid (stale / lost / phantom) — that is the
 * reference-model oracle's job in a later slice. Here we trust the document's claimed
 * {@code seq} and only assert internal self-consistency.
 */
public final class VerifiedValue {

    /** Field name of the embedded verification envelope. */
    public static final String VERIFY_FIELD = "_verify";
    /** Deterministic business fields bound by the checksum. */
    public static final String NAME_FIELD = "vName";
    public static final String AMOUNT_FIELD = "vAmount";

    private static final int CHECKSUM_HEX_LEN = 16;

    private VerifiedValue() {}

    /** A single self-consistency violation found by {@link #validate}. */
    public record Violation(String code, String detail) {}

    /**
     * Build a self-verifying document for {@code key} at sequence {@code seq}. {@code template}
     * (nullable) supplies extra container-specific fields; {@code pkField}/{@code pkValue}
     * (nullable for single-keyless containers) are written last so a template cannot override
     * the partition key and misroute the write.
     */
    public static Map<String, Object> build(String runId, String container, String key,
                                            String pkField, Object pkValue,
                                            int writer, long seq, Map<String, Object> template) {
        Map<String, Object> doc = new LinkedHashMap<>();
        // Template first, then every engine-controlled field, so a caller-supplied template can
        // never override id / pk / the derived fields / the envelope and forge a "valid" doc.
        if (template != null) {
            for (Map.Entry<String, Object> e : template.entrySet()) {
                doc.put(e.getKey(), e.getValue());
            }
        }
        doc.put("id", key);
        String name = derivedName(key, seq);
        long amount = derivedAmount(key, seq);
        doc.put(NAME_FIELD, name);
        doc.put(AMOUNT_FIELD, amount);
        if (pkField != null) {
            doc.put(pkField, pkValue);
        }
        Map<String, Object> verify = new LinkedHashMap<>();
        verify.put("runId", runId);
        verify.put("writer", writer);
        verify.put("seq", seq);
        verify.put("writtenAtNanos", System.nanoTime());
        verify.put("checksum", checksum(runId, container, key, writer, seq, name, amount));
        doc.put(VERIFY_FIELD, verify);
        return doc;
    }

    /** Deterministic display name for {@code (key, seq)}. */
    public static String derivedName(String key, long seq) {
        return "wl/" + key + "#" + seq;
    }

    /** Deterministic numeric field for {@code (key, seq)} — exercises a non-string value path. */
    public static long derivedAmount(String key, long seq) {
        return seq * 7L + Math.floorMod(stableHash(key), 1000);
    }

    /**
     * Validate the internal self-consistency of a returned document. Returns an empty list when
     * the document is a well-formed self-verifying value; otherwise one {@link Violation} per
     * problem. {@code container} participates in the checksum so a document copied verbatim from
     * another container would fail.
     */
    public static List<Violation> validate(String container, Map<String, Object> doc) {
        List<Violation> out = new ArrayList<>(2);
        if (doc == null) {
            out.add(new Violation("CHECKSUM_MISMATCH", "document is null"));
            return out;
        }
        if (!(doc.get(VERIFY_FIELD) instanceof Map<?, ?> verify)) {
            out.add(new Violation("CHECKSUM_MISMATCH", "missing _verify envelope"));
            return out;
        }
        String runId = asString(verify.get("runId"));
        Integer writer = asInt(verify.get("writer"));
        Long seq = asLong(verify.get("seq"));
        String storedChecksum = asString(verify.get("checksum"));
        String id = asString(doc.get("id"));
        if (runId == null || writer == null || seq == null || storedChecksum == null || id == null) {
            out.add(new Violation("CHECKSUM_MISMATCH", "incomplete _verify envelope"));
            return out;
        }
        String expectedName = derivedName(id, seq);
        long expectedAmount = derivedAmount(id, seq);
        Object actualName = doc.get(NAME_FIELD);
        Long actualAmount = asLong(doc.get(AMOUNT_FIELD));
        if (!expectedName.equals(actualName)) {
            out.add(new Violation("FIELD_MISMATCH",
                NAME_FIELD + " expected '" + expectedName + "' but got '" + actualName + "'"));
        }
        if (actualAmount == null || actualAmount.longValue() != expectedAmount) {
            out.add(new Violation("FIELD_MISMATCH",
                AMOUNT_FIELD + " expected " + expectedAmount + " but got " + actualAmount));
        }
        String expectedChecksum = checksum(runId, container, id, writer, seq, expectedName, expectedAmount);
        if (!expectedChecksum.equals(storedChecksum)) {
            out.add(new Violation("CHECKSUM_MISMATCH",
                "checksum mismatch — torn, partial, or forged write"));
        }
        return out;
    }

    /** The claimed sequence number of a self-verifying document, or {@code null} if absent. */
    public static Long seqOf(Map<String, Object> doc) {
        return doc != null && doc.get(VERIFY_FIELD) instanceof Map<?, ?> verify
            ? asLong(verify.get("seq"))
            : null;
    }

    /** True when the document carries a {@code _verify} envelope (i.e. the engine wrote it). */
    public static boolean isVerified(Map<String, Object> doc) {
        return doc != null && doc.get(VERIFY_FIELD) instanceof Map<?, ?>;
    }

    // ----- internals -----

    private static String checksum(String runId, String container, String key,
                                   int writer, long seq, String name, long amount) {
        String material = runId + "|" + container + "|" + key + "|" + writer + "|"
            + seq + "|" + name + "|" + amount;
        return sha256Hex(material).substring(0, CHECKSUM_HEX_LEN);
    }

    /** Stable across JVMs (unlike {@code String.hashCode} guarantees) — first 4 bytes of SHA-256.
     *  Package-private so the per-run verification state can map keys to partitions identically. */
    static int stableHash(String s) {
        byte[] d = sha256(s);
        return ((d[0] & 0xFF) << 24) | ((d[1] & 0xFF) << 16) | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
    }

    private static String sha256Hex(String s) {
        byte[] d = sha256(s);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static Integer asInt(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static Long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }
}
