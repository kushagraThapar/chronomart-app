package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.SqlParameter;
import com.azure.cosmos.models.SqlQuerySpec;
import com.chronomart.config.ChronomartProperties;
import com.chronomart.domain.Seller;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Repository
public class SellerRepo {

    private final CosmosAsyncContainer container;

    public SellerRepo(CosmosAsyncDatabase database, ChronomartProperties props) {
        this.container = database.getContainer(props.containers().sellers());
    }

    public Mono<Seller> get(String id) {
        return container.readItem(id, new PartitionKey(id), Seller.class)
            .map(resp -> resp.getItem())
            .onErrorResume(CosmosException.class,
                e -> e.getStatusCode() == 404 ? Mono.empty() : Mono.error(e));
    }

    public Flux<Seller> list(int limit) {
        SqlQuerySpec spec = new SqlQuerySpec(
            "SELECT TOP @n * FROM c ORDER BY c.id",
            List.of(new SqlParameter("@n", limit)));
        CosmosQueryRequestOptions opts = new CosmosQueryRequestOptions();
        // concatMap (not flatMap) preserves the ORDER BY across pages — flatMap allows
        // pages to interleave even though Flux.fromIterable is synchronous today.
        return container.queryItems(spec, opts, Seller.class)
            .byPage()
            .concatMap(page -> Flux.fromIterable(page.getResults()));
    }
}
