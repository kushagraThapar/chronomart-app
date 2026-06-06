package com.chronomart.repo;

import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.PartitionKeyBuilder;
import com.chronomart.config.ChronomartProperties;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

/**
 * Shared safety-rail bean used by every "execute a Cosmos operation given a caller-supplied
 * container name and partition key" surface: {@link QueryRunner}, {@code BulkRunner},
 * {@code BatchRunner}, {@code PatchRunner}.
 *
 * <p>Owns two responsibilities that should be enforced identically across all those runners:
 * <ul>
 *   <li><b>Container allow-list</b> — only the ten configured ChronoMart container names are
 *       accepted. Anything else throws {@link IllegalArgumentException} (→ HTTP 400 via the
 *       global exception handler).</li>
 *   <li><b>Partition-key parsing</b> — accepts either a single-level {@link String} or a
 *       {@link List} of {@code String|Number|Boolean} for hierarchical PKs. Numeric levels
 *       whose absolute value exceeds {@code 2^53} are rejected because
 *       {@code PartitionKeyBuilder} only accepts {@code double} and silently downcasting
 *       would route the request to the wrong partition.</li>
 * </ul>
 *
 * <p>Extracted from {@link QueryRunner} as part of PR5 so bulk/batch/patch runners can reuse
 * the exact same rules without duplication. {@link QueryRunner} now delegates here.
 */
@Component
public class ContainerAllowList {

    private final Set<String> allowed;

    public ContainerAllowList(ChronomartProperties props) {
        ChronomartProperties.Containers c = props.containers();
        this.allowed = Set.of(
            c.sellers(), c.products(), c.productsHpk(),
            c.customers(), c.orders(), c.reviews(),
            c.cart(), c.inventory(), c.productVectors(),
            c.changeFeedLease()
        );
    }

    public Set<String> names() {
        return allowed;
    }

    /**
     * Throws if {@code container} is not in the allow-list. Use this at the start of every
     * runner method so the error surface is consistent.
     */
    public void requireAllowed(String container) {
        if (container == null || !allowed.contains(container)) {
            throw new IllegalArgumentException(
                "container '" + container + "' is not in the allow-list: " + allowed);
        }
    }

    /**
     * Accepts either a {@link String} (single-level PK), a {@link List} of strings/numbers/
     * booleans (hierarchical PK levels), or {@code null}. {@code null} or an empty list
     * returns {@code null} so the caller can decide whether to require cross-partition.
     * Blank strings are rejected explicitly so they cannot silently degrade to "no PK".
     */
    public PartitionKey parse(Object raw) {
        if (raw == null) return null;
        if (raw instanceof String s) {
            if (s.isBlank()) {
                throw new IllegalArgumentException(
                    "partitionKey must not be blank — omit the field for cross-partition");
            }
            return new PartitionKey(s);
        }
        if (raw instanceof List<?> list) {
            if (list.isEmpty()) return null;
            PartitionKeyBuilder b = new PartitionKeyBuilder();
            for (Object v : list) {
                if (v == null) {
                    throw new IllegalArgumentException("partitionKey levels cannot contain null");
                }
                if (v instanceof String s)        b.add(s);
                else if (v instanceof Boolean bo) b.add(bo);
                else if (v instanceof Number n) {
                    // PartitionKeyBuilder only accepts double for numbers. For integral values
                    // that exceed double's safe-integer range (±2^53), silently downcasting
                    // would route the request to the wrong partition. Reject explicitly for
                    // Long (handling Long.MIN_VALUE separately since Math.abs(Long.MIN_VALUE)
                    // overflows) and BigInteger (Jackson uses these for JSON integers outside
                    // long range). Float/Double/BigDecimal pass through — they're already
                    // lossy by nature.
                    if (n instanceof BigInteger bi && bi.bitLength() > 53) {
                        throw new IllegalArgumentException(
                            "partitionKey numeric level " + bi + " exceeds the safe double range "
                                + "(±2^53); send it as a string to preserve precision");
                    }
                    if (n instanceof Long l && (l == Long.MIN_VALUE || Math.abs(l) > (1L << 53))) {
                        throw new IllegalArgumentException(
                            "partitionKey numeric level " + l + " exceeds the safe double range "
                                + "(±2^53); send it as a string to preserve precision");
                    }
                    b.add(n.doubleValue());
                }
                else throw new IllegalArgumentException(
                    "unsupported partitionKey level type: " + v.getClass().getName());
            }
            return b.build();
        }
        throw new IllegalArgumentException(
            "partitionKey must be a string or array of primitives, got " + raw.getClass().getName());
    }

    /**
     * Like {@link #parse(Object)} but throws when the result would be {@code null} — used by
     * surfaces (bulk, batch, patch) that require a partition key on every operation.
     */
    public PartitionKey parseRequired(Object raw) {
        PartitionKey pk = parse(raw);
        if (pk == null) {
            throw new IllegalArgumentException(
                "partitionKey is required for this operation (got null or empty)");
        }
        return pk;
    }
}
