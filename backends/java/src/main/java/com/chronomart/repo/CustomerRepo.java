package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.PartitionKey;
import com.chronomart.config.ChronomartProperties;
import com.chronomart.domain.Customer;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Repository for {@link Customer} (PK = {@code /id}). Only {@code create} and {@code get}
 * are exposed because that's the OpenAPI surface for {@code /customers} in v1.
 */
@Repository
public class CustomerRepo {

    private final CosmosAsyncContainer container;

    public CustomerRepo(CosmosAsyncDatabase database, ChronomartProperties props) {
        this.container = database.getContainer(props.containers().customers());
    }

    public Mono<Customer> get(String id) {
        return container.readItem(id, new PartitionKey(id), Customer.class)
            .map(resp -> resp.getItem())
            .onErrorResume(CosmosException.class,
                e -> e.getStatusCode() == 404 ? Mono.empty() : Mono.error(e));
    }

    public Mono<Customer> create(Customer customer) {
        return container.createItem(customer).map(resp -> resp.getItem());
    }
}
