package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.PartitionKeyBuilder;
import com.chronomart.config.ChronomartProperties;
import com.chronomart.domain.ProductHpk;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Repository for {@link ProductHpk} against the {@code ProductsHpk} container
 * (HPK = {@code /sellerId, /categoryId, /id}). Only point-read and upsert are exposed —
 * OpenAPI does not expose POST or DELETE on this resource.
 *
 * <p>Note: {@code createItem}/{@code upsertItem} extract the partition key directly from
 * the document via the container's HPK definition, so no explicit {@link PartitionKey} is
 * needed on writes. Reads use a 3-level {@link PartitionKeyBuilder} since the SDK has no
 * "read by document" overload.
 */
@Repository
public class ProductHpkRepo {

    private final CosmosAsyncContainer container;

    public ProductHpkRepo(CosmosAsyncDatabase database, ChronomartProperties props) {
        this.container = database.getContainer(props.containers().productsHpk());
    }

    public Mono<ProductHpk> get(String sellerId, String categoryId, String id) {
        // 2-level HPK on the vNext emulator (see ContainerInitializer.ensureHpk for the
        // /id-leaf deferral). The {@code id} arg is the document id passed to readItem,
        // not a PK level — Cosmos still routes a (sellerId, categoryId)-scoped read to
        // its single owning partition; adding /id as a 3rd PK level (planned for real
        // Cosmos) would only sharpen single-partition routing for skewed categories.
        PartitionKey pk = new PartitionKeyBuilder()
            .add(sellerId).add(categoryId).build();
        return container.readItem(id, pk, ProductHpk.class)
            .map(resp -> resp.getItem())
            .onErrorResume(CosmosException.class,
                e -> e.getStatusCode() == 404 ? Mono.empty() : Mono.error(e));
    }

    public Mono<ProductHpk> upsert(ProductHpk product) {
        return container.upsertItem(product).map(resp -> resp.getItem());
    }
}
