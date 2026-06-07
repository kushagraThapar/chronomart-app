package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.SqlParameter;
import com.azure.cosmos.models.SqlQuerySpec;
import com.chronomart.web.dto.VectorMatch;
import com.chronomart.web.dto.VectorSearchRequest;
import com.chronomart.web.dto.VectorSearchResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runner for {@code POST /api/v1/vector/search}. Executes a parameterized
 * {@code VectorDistance(...)} query against an allow-listed vector-enabled container.
 *
 * <p>Safety rails (all enforced here, mirroring the conventions in {@link QueryRunner}):
 * <ul>
 *   <li><b>Container allow-list</b> — {@link ContainerAllowList#requireAllowed(String)}.</li>
 *   <li><b>Provisioning gate</b> — when {@link VectorContainerStatus#isReady()} is
 *       {@code false}, the runner throws {@link IllegalStateException} so the controller
 *       can return a clean 501 instead of letting a raw Cosmos 404 leak out. The capability
 *       manifest's {@code vectorSearch} flag honors the same status, so the harness never
 *       lies about what it can deliver.</li>
 *   <li><b>Dimension check</b> — vector size MUST equal
 *       {@link ContainerInitializer#VECTOR_DIMENSIONS}. Without this, Cosmos returns an
 *       opaque server error after the request hits the wire.</li>
 *   <li><b>Bind once, reference twice</b> — the {@code @vec} parameter appears in both
 *       the SELECT projection and the ORDER BY so the optimizer can match them as the
 *       same call. Identical {@code VectorDistance(...)} expressions are how Cosmos
 *       picks up the DiskANN index path.</li>
 *   <li><b>TOP @k</b> — DiskANN requires a TOP/LIMIT to use the index; we always supply
 *       it and clamp to the OpenAPI documented range (1..100).</li>
 *   <li><b>Single page</b> — {@code byPage().next()} returns one page; we surface
 *       {@code requestCharge} so callers can see if RU drifts above the index-using
 *       ballpark (a high RU for a tiny container is the canary for "index not used").</li>
 * </ul>
 */
@Component
public class VectorSearchRunner {

    private static final int DEFAULT_K = 10;
    private static final int MAX_K = 100;

    private final CosmosAsyncDatabase database;
    private final ContainerAllowList allowList;
    private final VectorContainerStatus vectorStatus;

    public VectorSearchRunner(CosmosAsyncDatabase database,
                              ContainerAllowList allowList,
                              VectorContainerStatus vectorStatus) {
        this.database = database;
        this.allowList = allowList;
        this.vectorStatus = vectorStatus;
    }

    public boolean isReady() {
        return vectorStatus.isReady();
    }

    public Mono<VectorSearchResponse> search(VectorSearchRequest req) {
        try {
            allowList.requireAllowed(req.container());
        } catch (IllegalArgumentException e) {
            return Mono.error(e);
        }
        if (!vectorStatus.isReady()) {
            return Mono.error(new IllegalStateException(
                "vector search is not available: " + req.container() + " was not provisioned at startup "
                    + "(see /api/v1/_meta/diagnostics for the SDK call detail)"));
        }
        float[] vector = req.vector();
        if (vector == null || vector.length == 0) {
            return Mono.error(new IllegalArgumentException("vector must contain at least one element"));
        }
        if (vector.length != ContainerInitializer.VECTOR_DIMENSIONS) {
            return Mono.error(new IllegalArgumentException(
                "vector dimensions=" + vector.length + " does not match container dimensions="
                    + ContainerInitializer.VECTOR_DIMENSIONS + " (" + req.container() + ")"));
        }
        int k = req.k() == null ? DEFAULT_K : req.k();
        if (k < 1 || k > MAX_K) {
            return Mono.error(new IllegalArgumentException("k must be in [1, " + MAX_K + "]"));
        }

        // Bind @vec once, reference twice so the optimizer matches both VectorDistance calls
        // as the same expression and uses the DiskANN index. TOP @k is mandatory for DiskANN.
        String sql = "SELECT TOP @k c.id, c.productId, c.sellerId, c.name, c.embedding, "
            + "VectorDistance(c.embedding, @vec) AS score "
            + "FROM c "
            + "ORDER BY VectorDistance(c.embedding, @vec)";
        List<SqlParameter> params = List.of(
            new SqlParameter("@k", k),
            new SqlParameter("@vec", vector)
        );
        SqlQuerySpec spec = new SqlQuerySpec(sql, params);

        // Vector search is cross-partition by design — no PK supplied.
        CosmosQueryRequestOptions opts = new CosmosQueryRequestOptions();

        return database.getContainer(req.container())
            .queryItems(spec, opts, Object.class)
            .byPage(k)   // request one page sized to k (avoids prefetching extra pages)
            .next()
            .map(page -> {
                List<VectorMatch> matches = new ArrayList<>(page.getResults().size());
                for (Object row : page.getResults()) {
                    matches.add(toMatch(row));
                }
                return new VectorSearchResponse(matches, page.getRequestCharge());
            })
            .defaultIfEmpty(new VectorSearchResponse(List.of(), 0.0));
    }

    @SuppressWarnings("unchecked")
    private static VectorMatch toMatch(Object row) {
        if (!(row instanceof Map<?, ?> raw)) {
            return new VectorMatch(null, null, null, null, null, null);
        }
        Map<String, Object> doc = (Map<String, Object>) raw;
        // Lift denormalized fields out of the document for top-level access; keep the full
        // document under `document` for callers that want the embedding array or other
        // fields. LinkedHashMap preserves insertion order so the OpenAPI projection order
        // is what the UI sees.
        return new VectorMatch(
            asString(doc.get("id")),
            asString(doc.get("productId")),
            asString(doc.get("sellerId")),
            asString(doc.get("name")),
            asDouble(doc.get("score")),
            new LinkedHashMap<>(doc)
        );
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException nfe) {
            return null;
        }
    }
}
