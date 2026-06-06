package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.PartitionKeyBuilder;
import com.chronomart.config.ChronomartProperties;
import com.chronomart.domain.Order;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Repository for {@link Order} against the {@code Orders} container
 * (HPK = {@code /customerId, /yearMonth, /id}). Only point-read and upsert are exposed.
 */
@Repository
public class OrderRepo {

    private final CosmosAsyncContainer container;

    public OrderRepo(CosmosAsyncDatabase database, ChronomartProperties props) {
        this.container = database.getContainer(props.containers().orders());
    }

    public Mono<Order> get(String customerId, String yearMonth, String id) {
        // 2-level HPK on the vNext emulator (see ContainerInitializer.ensureHpk for the
        // /id-leaf deferral). The id arg is the document id; the partitionKey is the
        // (customerId, yearMonth) prefix.
        PartitionKey pk = new PartitionKeyBuilder()
            .add(customerId).add(yearMonth).build();
        return container.readItem(id, pk, Order.class)
            .map(resp -> resp.getItem())
            .onErrorResume(CosmosException.class,
                e -> e.getStatusCode() == 404 ? Mono.empty() : Mono.error(e));
    }

    public Mono<Order> upsert(Order order) {
        return container.upsertItem(order).map(resp -> resp.getItem());
    }
}
