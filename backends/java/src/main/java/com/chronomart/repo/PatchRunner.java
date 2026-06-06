package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.models.CosmosPatchItemRequestOptions;
import com.azure.cosmos.models.CosmosPatchOperations;
import com.azure.cosmos.models.PartitionKey;
import com.chronomart.web.dto.PatchOperation;
import com.chronomart.web.dto.PatchRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * Executes POST /patch: apply a list of {@link PatchOperation} ops to a single document.
 *
 * <p>All six Cosmos patch op verbs are mapped natively to the SDK:
 * <ul>
 *   <li>{@code add(path, value)} — insert new field or array element</li>
 *   <li>{@code set(path, value)} — set field (creates intermediate fields if missing)</li>
 *   <li>{@code replace(path, value)} — replace existing field; fails if missing</li>
 *   <li>{@code remove(path)} — remove existing field; fails if missing</li>
 *   <li>{@code increment(path, long|double)} — atomic numeric increment</li>
 *   <li>{@code move(from, path)} — move value from {@code from} to {@code path}; atomic</li>
 * </ul>
 *
 * <p>For {@code increment}: numeric values are routed to the {@code long} overload when the
 * input is an integral type (Integer/Long/Short/Byte/BigInteger that fits in long), otherwise
 * to the {@code double} overload. {@code BigInteger} values outside the long range are
 * rejected because silent downcast would lose precision in a way the caller wouldn't expect.
 *
 * <p>{@code ifMatchEtag} and {@code filterPredicate} are both honored via
 * {@link CosmosPatchItemRequestOptions}.
 */
@Component
public class PatchRunner {

    private final CosmosAsyncDatabase database;
    private final ContainerAllowList allowList;

    public PatchRunner(CosmosAsyncDatabase database, ContainerAllowList allowList) {
        this.database = database;
        this.allowList = allowList;
    }

    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> run(PatchRequest req) {
        try {
            allowList.requireAllowed(req.container());
        } catch (IllegalArgumentException e) {
            return Mono.error(e);
        }
        PartitionKey pk;
        try {
            pk = allowList.parseRequired(req.partitionKey());
        } catch (IllegalArgumentException e) {
            return Mono.error(e);
        }

        CosmosPatchOperations cosmosPatch;
        try {
            cosmosPatch = buildOperations(req);
        } catch (IllegalArgumentException e) {
            return Mono.error(e);
        }

        CosmosPatchItemRequestOptions opts = new CosmosPatchItemRequestOptions();
        if (req.ifMatchEtag() != null && !req.ifMatchEtag().isBlank()) {
            opts.setIfMatchETag(req.ifMatchEtag());
        }
        if (req.filterPredicate() != null && !req.filterPredicate().isBlank()) {
            opts.setFilterPredicate(req.filterPredicate());
        }

        return database.getContainer(req.container())
            .patchItem(req.id(), pk, cosmosPatch, opts, (Class<Map<String, Object>>) (Class<?>) Map.class)
            .map(resp -> {
                Map<String, Object> item = resp.getItem();
                return item != null ? item : new HashMap<>();
            });
    }

    private static CosmosPatchOperations buildOperations(PatchRequest req) {
        CosmosPatchOperations p = CosmosPatchOperations.create();
        for (PatchOperation op : req.operations()) {
            String path = op.path();
            switch (op.op()) {
                case "add":
                    requireValue(op, "add");
                    p.add(path, op.value());
                    break;
                case "set":
                    requireValue(op, "set");
                    p.set(path, op.value());
                    break;
                case "replace":
                    requireValue(op, "replace");
                    p.replace(path, op.value());
                    break;
                case "remove":
                    requireNoValue(op, "remove");
                    p.remove(path);
                    break;
                case "increment":
                    requireValue(op, "increment");
                    applyIncrement(p, path, op.value());
                    break;
                case "move":
                    if (op.from() == null || op.from().isBlank()) {
                        throw new IllegalArgumentException(
                            "move op requires a non-blank 'from' path (source)");
                    }
                    if (!op.from().startsWith("/")) {
                        throw new IllegalArgumentException(
                            "move 'from' path must start with /");
                    }
                    requireNoValue(op, "move");
                    p.move(op.from(), path);
                    break;
                default:
                    // @Pattern on the DTO should make this unreachable.
                    throw new IllegalArgumentException("unknown patch op: " + op.op());
            }
        }
        return p;
    }

    private static void requireValue(PatchOperation op, String name) {
        if (op.value() == null) {
            throw new IllegalArgumentException(name + " op requires a 'value'");
        }
    }

    private static void requireNoValue(PatchOperation op, String name) {
        if (op.value() != null) {
            throw new IllegalArgumentException(name + " op must not carry a 'value' field");
        }
    }

    /**
     * Route to the correct {@code increment} overload. Integer-typed inputs go through the
     * {@code long} variant to preserve precision; floating-point or out-of-range integers
     * use the {@code double} variant (with explicit rejection of very large BigIntegers).
     */
    private static void applyIncrement(CosmosPatchOperations p, String path, Object value) {
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException(
                "increment value must be numeric, got " + value.getClass().getName());
        }
        if (n instanceof Long l) {
            p.increment(path, l);
            return;
        }
        if (n instanceof Integer || n instanceof Short || n instanceof Byte) {
            p.increment(path, n.longValue());
            return;
        }
        if (n instanceof BigInteger bi) {
            if (bi.bitLength() > 63) {
                throw new IllegalArgumentException(
                    "increment value " + bi + " exceeds the long range; use a string-typed field instead");
            }
            p.increment(path, bi.longValueExact());
            return;
        }
        // Double, Float, BigDecimal, etc → lossy by nature, send as double.
        p.increment(path, n.doubleValue());
    }
}
