package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.models.CosmosBatch;
import com.azure.cosmos.models.CosmosBatchItemRequestOptions;
import com.azure.cosmos.models.CosmosBatchOperationResult;
import com.azure.cosmos.models.PartitionKey;
import com.chronomart.web.dto.BatchOperation;
import com.chronomart.web.dto.BatchRequest;
import com.chronomart.web.dto.BatchResponse;
import com.chronomart.web.dto.BulkResultItem;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes POST /batch: a transactional batch within a single partition. All operations
 * commit together or none do.
 *
 * <p>Per-op semantics on rollback (per Cosmos contract):
 * <ul>
 *   <li>The operation that triggered the abort returns its real status (e.g. 412 on
 *       optimistic-concurrency failure, 409 on duplicate key, 404 on replace-not-found).</li>
 *   <li>Every other operation in the batch returns {@code 424} (FailedDependency).</li>
 * </ul>
 *
 * <p>{@code partitionKey} is enforced at the batch level: any per-op {@code partitionKey}
 * is rejected with HTTP 400 — there's no meaningful "per-op PK" inside a transactional
 * batch, and accepting one would imply transactions span partitions (they don't).
 *
 * <p>The controller maps {@code success=false} to <b>HTTP 409</b>; the body preserves the
 * SDK's batch-level {@code statusCode} so callers can distinguish 412 from 409 from 404 etc.
 */
@Component
public class BatchRunner {

    private final CosmosAsyncDatabase database;
    private final ContainerAllowList allowList;

    public BatchRunner(CosmosAsyncDatabase database, ContainerAllowList allowList) {
        this.database = database;
        this.allowList = allowList;
    }

    public Mono<BatchResponse> run(BatchRequest req) {
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

        CosmosBatch batch = CosmosBatch.createCosmosBatch(pk);
        // Keep op verbs by index so result rows can echo what was requested.
        List<String> opVerbs = new ArrayList<>(req.operations().size());
        List<String> opIds = new ArrayList<>(req.operations().size());
        try {
            for (BatchOperation o : req.operations()) {
                if (o.partitionKey() != null) {
                    throw new IllegalArgumentException(
                        "per-op 'partitionKey' is not allowed inside a transactional batch — "
                            + "the batch-level partitionKey applies to every operation");
                }
                addToBatch(batch, o);
                opVerbs.add(o.op());
                opIds.add(resolveResourceId(o));
            }
        } catch (IllegalArgumentException e) {
            return Mono.error(e);
        }

        return database.getContainer(req.container())
            .executeCosmosBatch(batch)
            .map(resp -> {
                List<CosmosBatchOperationResult> results = resp.getResults();
                List<BulkResultItem> rows = new ArrayList<>(results.size());
                for (int i = 0; i < results.size(); i++) {
                    CosmosBatchOperationResult r = results.get(i);
                    String verb = i < opVerbs.size() ? opVerbs.get(i) : null;
                    String rid = i < opIds.size() ? opIds.get(i) : null;
                    String err = r.isSuccessStatusCode() ? null : statusErrorText(r.getStatusCode());
                    rows.add(new BulkResultItem(verb, r.getStatusCode(), r.getRequestCharge(), rid, err));
                }
                return new BatchResponse(
                    resp.getStatusCode(),
                    resp.isSuccessStatusCode(),
                    rows,
                    resp.getRequestCharge());
            });
    }

    /** Append one DTO op to the batch, applying ifMatchEtag if present. */
    private static void addToBatch(CosmosBatch batch, BatchOperation o) {
        CosmosBatchItemRequestOptions opts = null;
        if (o.ifMatchEtag() != null && !o.ifMatchEtag().isBlank()) {
            opts = new CosmosBatchItemRequestOptions().setIfMatchETag(o.ifMatchEtag());
        }
        switch (o.op()) {
            case "create": {
                requireDocument(o, "create");
                Map<String, Object> doc = ensureIdInDocument(o, /*requireId=*/ true);
                if (opts != null) batch.createItemOperation(doc, opts);
                else              batch.createItemOperation(doc);
                break;
            }
            case "upsert": {
                requireDocument(o, "upsert");
                Map<String, Object> doc = ensureIdInDocument(o, /*requireId=*/ false);
                if (opts != null) batch.upsertItemOperation(doc, opts);
                else              batch.upsertItemOperation(doc);
                break;
            }
            case "replace": {
                requireDocument(o, "replace");
                String id = resolveId(o, "replace");
                Map<String, Object> doc = ensureIdInDocument(o, /*requireId=*/ false);
                if (opts != null) batch.replaceItemOperation(id, doc, opts);
                else              batch.replaceItemOperation(id, doc);
                break;
            }
            case "delete": {
                String id = resolveId(o, "delete");
                if (opts != null) batch.deleteItemOperation(id, opts);
                else              batch.deleteItemOperation(id);
                break;
            }
            default:
                throw new IllegalArgumentException("unknown batch op: " + o.op());
        }
    }

    private static void requireDocument(BatchOperation o, String op) {
        if (o.document() == null || o.document().isEmpty()) {
            throw new IllegalArgumentException(op + " op requires a non-empty 'document'");
        }
    }

    private static String resolveId(BatchOperation o, String op) {
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

    private static Map<String, Object> ensureIdInDocument(BatchOperation o, boolean requireId) {
        Map<String, Object> doc = o.document();
        String explicit = trimToNull(o.id());
        String fromDoc = trimToNull(stringOrNull(doc.get("id")));
        if (explicit != null && fromDoc != null && !explicit.equals(fromDoc)) {
            throw new IllegalArgumentException(
                o.op() + " op has conflicting id values: id='" + explicit + "' vs document.id='" + fromDoc + "'");
        }
        String resolved = explicit != null ? explicit : fromDoc;
        if (resolved == null && requireId) {
            throw new IllegalArgumentException(
                o.op() + " op requires an 'id' (either as the op field or inside document)");
        }
        if (resolved != null && !resolved.equals(fromDoc)) {
            Map<String, Object> copy = new LinkedHashMap<>(doc);
            copy.put("id", resolved);
            return copy;
        }
        return doc;
    }

    private static String resolveResourceId(BatchOperation o) {
        String explicit = trimToNull(o.id());
        if (explicit != null) return explicit;
        if (o.document() == null) return null;
        return trimToNull(stringOrNull(o.document().get("id")));
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : o.toString();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String statusErrorText(int code) {
        return switch (code) {
            case 404 -> "NotFound (document missing for replace/delete)";
            case 409 -> "Conflict (duplicate key on create)";
            case 412 -> "PreconditionFailed (ifMatchEtag mismatch)";
            case 424 -> "FailedDependency (transaction rolled back due to another op)";
            default  -> "status " + code;
        };
    }
}
