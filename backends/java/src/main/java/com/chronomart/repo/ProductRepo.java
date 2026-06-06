package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.SqlParameter;
import com.azure.cosmos.models.SqlQuerySpec;
import com.chronomart.config.ChronomartProperties;
import com.chronomart.domain.Product;
import com.chronomart.web.dto.Page;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Repository for {@link Product} (PK = {@code /sellerId}). Point CRUD + a single-page
 * list with optional {@code sellerId} filter and Cosmos continuation token pass-through.
 */
@Repository
public class ProductRepo {

    private final CosmosAsyncContainer container;

    public ProductRepo(CosmosAsyncDatabase database, ChronomartProperties props) {
        this.container = database.getContainer(props.containers().products());
    }

    public Mono<Product> get(String sellerId, String id) {
        return container.readItem(id, new PartitionKey(sellerId), Product.class)
            .map(resp -> resp.getItem())
            .onErrorResume(CosmosException.class,
                e -> e.getStatusCode() == 404 ? Mono.empty() : Mono.error(e));
    }

    public Mono<Product> create(Product product) {
        // Cosmos extracts PK from the document via the container's PK definition (/sellerId).
        return container.createItem(product).map(resp -> resp.getItem());
    }

    public Mono<Product> upsert(Product product) {
        return container.upsertItem(product).map(resp -> resp.getItem());
    }

    public Mono<Void> delete(String sellerId, String id) {
        return container.deleteItem(id, new PartitionKey(sellerId), new CosmosItemRequestOptions())
            // Idempotent: a re-issued delete on a missing/just-deleted item still returns 204.
            .onErrorResume(CosmosException.class,
                e -> e.getStatusCode() == 404 ? Mono.empty() : Mono.error(e))
            .then();
    }

    /**
     * Paged list. When {@code sellerId} is supplied the query is single-partition (cheap);
     * when {@code null} it falls back to a cross-partition scan ordered by {@code id}.
     */
    public Mono<Page<Product>> list(String sellerId, int pageSize, String continuation) {
        SqlQuerySpec spec;
        CosmosQueryRequestOptions opts = new CosmosQueryRequestOptions();
        if (sellerId != null && !sellerId.isBlank()) {
            spec = new SqlQuerySpec(
                "SELECT * FROM c WHERE c.sellerId = @sellerId ORDER BY c.id",
                List.of(new SqlParameter("@sellerId", sellerId)));
            opts.setPartitionKey(new PartitionKey(sellerId));
        } else {
            spec = new SqlQuerySpec("SELECT * FROM c ORDER BY c.id");
        }
        return container.queryItems(spec, opts, Product.class)
            .byPage(continuation, pageSize)
            .next()
            .map(page -> new Page<>(new ArrayList<>(page.getResults()), page.getContinuationToken()))
            .defaultIfEmpty(new Page<>(List.of(), null));
    }
}
