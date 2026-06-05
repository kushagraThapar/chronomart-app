package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.PartitionKeyBuilder;
import com.azure.cosmos.models.SqlParameter;
import com.azure.cosmos.models.SqlQuerySpec;
import com.chronomart.config.ChronomartProperties;
import com.chronomart.web.dto.QueryRequest;
import com.chronomart.web.dto.QueryResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generic runner for {@code POST /queries/run}. Executes a parameterized SQL query against
 * an allow-listed container with optional partition-key scoping and continuation tokens.
 *
 * <p>Safety rails (all enforced here, not in the controller):
 * <ul>
 *   <li><b>Container allow-list</b> — only the ten configured ChronoMart container names
 *       are accepted. Anything else returns {@link IllegalArgumentException} (→ 400).</li>
 *   <li><b>Cross-partition opt-in</b> — when no {@code partitionKey} is supplied and
 *       {@code enableCrossPartition} is not {@code true}, the request is rejected.</li>
 *   <li><b>Parameterised SQL</b> — query text is taken verbatim; bind values are pushed
 *       through {@link SqlParameter} so callers cannot inject literals.</li>
 *   <li><b>Page-bounded execution</b> — we read exactly one page via {@code byPage(...).next()}
 *       so the caller controls iteration via the returned continuation token.</li>
 * </ul>
 */
@Component
public class QueryRunner {

    private final CosmosAsyncDatabase database;
    private final Set<String> allowedContainers;

    public QueryRunner(CosmosAsyncDatabase database, ChronomartProperties props) {
        this.database = database;
        ChronomartProperties.Containers c = props.containers();
        this.allowedContainers = Set.of(
            c.sellers(), c.products(), c.productsHpk(),
            c.customers(), c.orders(), c.reviews(),
            c.cart(), c.inventory(), c.productVectors(),
            c.changeFeedLease()
        );
    }

    public Set<String> allowedContainers() {
        return allowedContainers;
    }

    public Mono<QueryResponse> run(QueryRequest req) {
        if (!allowedContainers.contains(req.container())) {
            return Mono.error(new IllegalArgumentException(
                "container '" + req.container() + "' is not in the allow-list: " + allowedContainers));
        }
        PartitionKey pk = buildPartitionKey(req.partitionKey());
        if (pk == null && !req.crossPartitionEnabled()) {
            return Mono.error(new IllegalArgumentException(
                "partitionKey is required unless enableCrossPartition=true"));
        }

        SqlQuerySpec spec = new SqlQuerySpec(req.query(), toSqlParameters(req.parameters()));
        CosmosQueryRequestOptions opts = new CosmosQueryRequestOptions();
        if (pk != null) {
            opts.setPartitionKey(pk);
        }
        // -1 is the SDK sentinel for "let SDK decide" and is the OpenAPI documented default.
        // Treat null as -1; reject anything below -1 explicitly.
        int maxConcurrency = req.maxConcurrency() == null ? -1 : req.maxConcurrency();
        if (maxConcurrency < -1) {
            return Mono.error(new IllegalArgumentException(
                "maxConcurrency must be -1 (SDK default) or greater"));
        }
        opts.setMaxDegreeOfParallelism(maxConcurrency);

        // Use Object.class so the runner returns whatever the query projects — a Map for
        // SELECT *, a scalar Number for SELECT VALUE COUNT(1), a String for VALUE c.name, etc.
        return database.getContainer(req.container())
            .queryItems(spec, opts, Object.class)
            .byPage(req.continuation(), req.effectivePageSize())
            .next()
            .map(page -> new QueryResponse(
                new ArrayList<>(page.getResults()),
                page.getContinuationToken(),
                page.getRequestCharge(),
                null))
            .defaultIfEmpty(new QueryResponse(List.of(), null, 0.0, null));
    }

    private static List<SqlParameter> toSqlParameters(List<QueryRequest.Parameter> params) {
        if (params == null || params.isEmpty()) {
            return List.of();
        }
        Map<String, SqlParameter> byName = new LinkedHashMap<>();
        for (QueryRequest.Parameter p : params) {
            if (p == null || p.name() == null || p.name().isBlank()) {
                throw new IllegalArgumentException("query parameter name must be non-blank");
            }
            String name = p.name().startsWith("@") ? p.name() : "@" + p.name();
            if (byName.containsKey(name)) {
                throw new IllegalArgumentException("duplicate query parameter: " + name);
            }
            byName.put(name, new SqlParameter(name, p.value()));
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * Accepts either a {@link String} (single-level PK), a {@link List} of strings/numbers
     * (hierarchical PK levels), or {@code null}. {@code null} or an empty list returns
     * {@code null} so the caller can decide whether to require cross-partition. Blank
     * strings are rejected explicitly so they cannot silently degrade to "no PK".
     */
    private static PartitionKey buildPartitionKey(Object raw) {
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
                if (v instanceof String s)      b.add(s);
                else if (v instanceof Boolean bo) b.add(bo);
                else if (v instanceof Number n)   b.add(n.doubleValue());
                else throw new IllegalArgumentException(
                    "unsupported partitionKey level type: " + v.getClass().getName());
            }
            return b.build();
        }
        throw new IllegalArgumentException(
            "partitionKey must be a string or array of primitives, got " + raw.getClass().getName());
    }
}
