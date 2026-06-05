package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.PartitionKey;
import com.chronomart.config.ChronomartProperties;
import com.chronomart.domain.Inventory;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Repository for {@link Inventory} (PK = {@code /sellerId}). Point-read and upsert only —
 * inventory mutations land here via PUT.
 */
@Repository
public class InventoryRepo {

    private final CosmosAsyncContainer container;

    public InventoryRepo(CosmosAsyncDatabase database, ChronomartProperties props) {
        this.container = database.getContainer(props.containers().inventory());
    }

    public Mono<Inventory> get(String sellerId, String id) {
        return container.readItem(id, new PartitionKey(sellerId), Inventory.class)
            .map(resp -> resp.getItem())
            .onErrorResume(CosmosException.class,
                e -> e.getStatusCode() == 404 ? Mono.empty() : Mono.error(e));
    }

    public Mono<Inventory> upsert(Inventory inventory) {
        return container.upsertItem(inventory).map(resp -> resp.getItem());
    }
}
