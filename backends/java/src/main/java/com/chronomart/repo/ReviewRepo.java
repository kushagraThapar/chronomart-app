package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.PartitionKey;
import com.chronomart.config.ChronomartProperties;
import com.chronomart.domain.Review;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Repository for {@link Review} (PK = {@code /productId}). Only point-read and upsert are
 * exposed — review creation is via PUT (upsert) per the OpenAPI contract.
 */
@Repository
public class ReviewRepo {

    private final CosmosAsyncContainer container;

    public ReviewRepo(CosmosAsyncDatabase database, ChronomartProperties props) {
        this.container = database.getContainer(props.containers().reviews());
    }

    public Mono<Review> get(String productId, String id) {
        return container.readItem(id, new PartitionKey(productId), Review.class)
            .map(resp -> resp.getItem())
            .onErrorResume(CosmosException.class,
                e -> e.getStatusCode() == 404 ? Mono.empty() : Mono.error(e));
    }

    public Mono<Review> upsert(Review review) {
        return container.upsertItem(review).map(resp -> resp.getItem());
    }
}
