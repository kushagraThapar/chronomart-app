package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.PartitionKey;
import com.chronomart.config.ChronomartProperties;
import com.chronomart.domain.Cart;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Repository for {@link Cart} against the {@code Cart} container (PK = {@code /customerId}).
 *
 * <p>TTL semantics: the container's {@code defaultTimeToLive} is 7 days (installed by
 * {@link ContainerInitializer}). When the document's {@code ttl} field is set on
 * {@code upsert}, it overrides the container default for that document — this is a
 * <em>document-level field on the JSON</em>, not a request option. Cosmos picks it up
 * automatically; this repo does no special handling.
 */
@Repository
public class CartRepo {

    private final CosmosAsyncContainer container;

    public CartRepo(CosmosAsyncDatabase database, ChronomartProperties props) {
        this.container = database.getContainer(props.containers().cart());
    }

    public Mono<Cart> get(String customerId) {
        return container.readItem(customerId, new PartitionKey(customerId), Cart.class)
            .map(resp -> resp.getItem())
            .onErrorResume(CosmosException.class,
                e -> e.getStatusCode() == 404 ? Mono.empty() : Mono.error(e));
    }

    public Mono<Cart> upsert(Cart cart) {
        return container.upsertItem(cart).map(resp -> resp.getItem());
    }
}
