package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosBulkExecutionOptions;
import com.azure.cosmos.models.CosmosBulkItemResponse;
import com.azure.cosmos.models.CosmosBulkOperationResponse;
import com.azure.cosmos.models.CosmosBulkOperations;
import com.azure.cosmos.models.CosmosItemOperation;
import com.azure.cosmos.models.PartitionKey;
import com.chronomart.web.dto.BulkOperation;
import com.chronomart.web.dto.BulkRequest;
import com.chronomart.web.dto.BulkResponse;
import com.chronomart.web.dto.BulkResultItem;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes POST /bulk: a list of independent create/upsert/replace/delete operations against
 * a single allow-listed container. Each operation can target any partition key. Per-item
 * statuses (including failures) are returned in input order; an overall HTTP 200 means the
 * request itself was well-formed, not that every operation succeeded.
 *
 * <p>Correlation: each input operation is tagged with its index as the SDK "context" via
 * {@link CosmosBulkOperations#getCreateItemOperation(Object, PartitionKey, Object)} (and
 * friends). The SDK echoes that index back on each {@link CosmosBulkOperationResponse}, so
 * we can re-order results to match the request even though the bulk executor may interleave
 * operations across partitions and micro-batches.
 *
 * <p>Per-op failure handling: the SDK does NOT throw on a single failed item; it surfaces
 * the failure either as {@code response.getException()} or as a {@code response.getResponse()}
 * with a non-2xx {@code statusCode}. We check both and never let one bad item poison the
 * whole Flux.
 *
 * <p>Concurrency: {@link CosmosBulkExecutionOptions#setMaxMicroBatchConcurrency} accepts
 * 1..5 inclusive. We treat {@code null} or {@code -1} as "do not set" (SDK default applies)
 * and reject anything outside that range with HTTP 400.
 */
@Component
public class BulkRunner {

    private final CosmosAsyncDatabase database;
    private final ContainerAllowList allowList;

    public BulkRunner(CosmosAsyncDatabase database, ContainerAllowList allowList) {
        this.database = database;
        this.allowList = allowList;
    }

    public Mono<BulkResponse> run(BulkRequest req) {
        try {
            allowList.requireAllowed(req.container());
        } catch (IllegalArgumentException e) {
            return Mono.error(e);
        }

        CosmosBulkExecutionOptions opts = new CosmosBulkExecutionOptions();
        Integer mc = req.maxConcurrency();
        if (mc != null && mc != -1) {
            if (mc < 1 || mc > 5) {
                return Mono.error(new IllegalArgumentException(
                    "maxConcurrency must be -1 (SDK default) or in [1, 5], got " + mc));
            }
            opts.setMaxMicroBatchConcurrency(mc);
        }

        List<CosmosItemOperation> ops = new ArrayList<>(req.operations().size());
        // Per-index resolved ids — bulk SDK ops for create/upsert don't carry an id, so
        // CosmosItemOperation.getId() returns null and we'd lose the resourceId for the
        // result row. Keep them on the side.
        Map<Integer, String> resolvedIds = new HashMap<>();
        try {
            for (int i = 0; i < req.operations().size(); i++) {
                BulkOperation o = req.operations().get(i);
                PartitionKey pk = allowList.parseRequired(o.partitionKey());
                CosmosItemOperation built = buildOperation(o, pk, i);
                ops.add(built);
                resolvedIds.put(i, firstNonNull(
                    built.getId(),
                    trimToNull(o.id()),
                    o.document() == null ? null : trimToNull(stringOrNull(o.document().get("id")))));
            }
        } catch (IllegalArgumentException e) {
            return Mono.error(e);
        }

        return database.getContainer(req.container())
            .<Integer>executeBulkOperations(Flux.fromIterable(ops), opts)
            .map(resp -> toResult(resp, resolvedIds))
            .sort(Comparator.comparingInt(r -> (Integer) r[0]))
            .collectList()
            .map(rows -> {
                List<BulkResultItem> results = new ArrayList<>(rows.size());
                double totalRu = 0.0;
                for (Object[] row : rows) {
                    BulkResultItem item = (BulkResultItem) row[1];
                    results.add(item);
                    totalRu += item.requestCharge();
                }
                return new BulkResponse(results, totalRu);
            });
    }

    private static String firstNonNull(String... vals) {
        for (String v : vals) if (v != null) return v;
        return null;
    }

    /** Build a CosmosItemOperation from a BulkOperation DTO and tag it with the input index. */
    private static CosmosItemOperation buildOperation(BulkOperation o, PartitionKey pk, int index) {
        String op = o.op();
        Map<String, Object> doc = o.document();
        switch (op) {
            case "create":
                requireDocument(o, "create");
                return CosmosBulkOperations.getCreateItemOperation(
                    ensureIdInDocument(o, doc), pk, Integer.valueOf(index));
            case "upsert":
                requireDocument(o, "upsert");
                return CosmosBulkOperations.getUpsertItemOperation(
                    ensureIdInDocument(o, doc), pk, Integer.valueOf(index));
            case "replace":
                requireDocument(o, "replace");
                String replaceId = resolveId(o, "replace");
                return CosmosBulkOperations.getReplaceItemOperation(
                    replaceId, ensureIdInDocument(o, doc), pk, Integer.valueOf(index));
            case "delete":
                String deleteId = resolveId(o, "delete");
                return CosmosBulkOperations.getDeleteItemOperation(
                    deleteId, pk, Integer.valueOf(index));
            default:
                // @Pattern on the DTO should make this unreachable, but be explicit.
                throw new IllegalArgumentException("unknown bulk op: " + op);
        }
    }

    private static void requireDocument(BulkOperation o, String op) {
        if (o.document() == null || o.document().isEmpty()) {
            throw new IllegalArgumentException(op + " op requires a non-empty 'document'");
        }
    }

    /**
     * Resolve the operation's target {@code id} from the explicit {@code id} field or
     * {@code document.id}. If both are present they must agree (silent precedence would
     * mask caller bugs).
     */
    private static String resolveId(BulkOperation o, String op) {
        String explicit = trimToNull(o.id());
        String fromDoc = o.document() == null ? null : trimToNull(stringOrNull(o.document().get("id")));
        if (explicit != null && fromDoc != null && !explicit.equals(fromDoc)) {
            throw new IllegalArgumentException(
                op + " op has conflicting id values: id='" + explicit + "' vs document.id='" + fromDoc + "'");
        }
        String resolved = explicit != null ? explicit : fromDoc;
        if (resolved == null) {
            throw new IllegalArgumentException(
                op + " op requires an 'id' (either as the op field or inside document)");
        }
        return resolved;
    }

    /**
     * For create/upsert/replace, ensure the document has an {@code id} field set to the
     * resolved id. This both satisfies Cosmos (which requires id in the document body) and
     * keeps {@link CosmosItemOperation#getId()} populated for correlation/logging.
     */
    private static Map<String, Object> ensureIdInDocument(BulkOperation o, Map<String, Object> doc) {
        String explicit = trimToNull(o.id());
        String fromDoc = trimToNull(stringOrNull(doc.get("id")));
        if (explicit != null && fromDoc != null && !explicit.equals(fromDoc)) {
            throw new IllegalArgumentException(
                o.op() + " op has conflicting id values: id='" + explicit + "' vs document.id='" + fromDoc + "'");
        }
        String resolved = explicit != null ? explicit : fromDoc;
        if (resolved == null && "create".equals(o.op())) {
            // For create, Cosmos requires an id in the document. upsert/replace also need
            // one but we let the SDK fail if explicitly omitted — most callers send it.
            throw new IllegalArgumentException(
                "create op requires an 'id' (either as the op field or inside document)");
        }
        if (resolved != null && !resolved.equals(fromDoc)) {
            Map<String, Object> copy = new LinkedHashMap<>(doc);
            copy.put("id", resolved);
            return copy;
        }
        return doc;
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : o.toString();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Convert a single {@link CosmosBulkOperationResponse} into an indexed result row.
     * Returns {@code Object[2]} of {@code [Integer index, BulkResultItem]} so the caller
     * can sort by index regardless of SDK ordering.
     */
    private static Object[] toResult(CosmosBulkOperationResponse<Integer> resp,
                                      Map<Integer, String> resolvedIds) {
        CosmosItemOperation op = resp.getOperation();
        Integer idx = op.getContext();
        String opVerb = opVerbOf(op);
        // Prefer the operation's own id (delete/replace populate it); fall back to the
        // resolved id captured at build time (create/upsert don't surface an id on the op).
        String resourceId = op.getId();
        if (resourceId == null && idx != null) {
            resourceId = resolvedIds.get(idx);
        }

        Exception ex = resp.getException();
        if (ex != null) {
            int status = (ex instanceof CosmosException ce) ? ce.getStatusCode() : 500;
            double charge = (ex instanceof CosmosException ce) ? ce.getRequestCharge() : 0.0;
            return new Object[] { idx, new BulkResultItem(opVerb, status, charge, resourceId, ex.getMessage()) };
        }
        CosmosBulkItemResponse r = resp.getResponse();
        if (r == null) {
            return new Object[] { idx, new BulkResultItem(opVerb, 500, 0.0, resourceId, "no response and no exception from SDK") };
        }
        String error = r.isSuccessStatusCode() ? null : ("status " + r.getStatusCode());
        return new Object[] { idx, new BulkResultItem(opVerb, r.getStatusCode(), r.getRequestCharge(), resourceId, error) };
    }

    private static String opVerbOf(CosmosItemOperation op) {
        return switch (op.getOperationType()) {
            case CREATE  -> "create";
            case UPSERT  -> "upsert";
            case REPLACE -> "replace";
            case DELETE  -> "delete";
            case READ    -> "read";
            case PATCH   -> "patch";
        };
    }
}
