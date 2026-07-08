package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.PartitionKey;
import com.chronomart.config.ChronomartProperties;
import com.chronomart.web.dto.WorkloadStep;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/**
 * Retry coverage for {@link WorkloadEngine#executeCartUpsert}.
 *
 * <p>A concurrent upsert of the same not-yet-existent id races as two inserts; the loser gets a
 * transient <b>409 "ID collision on Create"</b> that the Cosmos SDK does not retry (see
 * {@code cosmosdb-design-docs/17-status-codes-and-sdk-retries.md} §2). Once the winning insert
 * commits, the doc exists, so re-running the upsert lands as a conflict-free replace. These tests
 * pin that behaviour: 409 is retried to success, a stuck 409 still surfaces the real error, and a
 * non-409 failure is never retried (so genuine errors aren't masked).
 */
class WorkloadEngineCartUpsertRetryTest {

    private static final WorkloadStep CART_STEP =
        new WorkloadStep("cartUpsert", "Cart", 1, Map.of("customerIds", List.of("load-cust-A")));

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static WorkloadEngine engineReturning(Mono<CosmosItemResponse<Object>> upsertMono) {
        CosmosAsyncDatabase database = Mockito.mock(CosmosAsyncDatabase.class);
        CosmosAsyncContainer container = Mockito.mock(CosmosAsyncContainer.class);
        Mockito.when(database.getContainer("Cart")).thenReturn(container);
        Mockito.when(container.upsertItem(any(), any(PartitionKey.class), any(CosmosItemRequestOptions.class)))
            .thenReturn((Mono) upsertMono);

        ChronomartProperties props = new ChronomartProperties(
            null, null, null, null, false, null,
            ChronomartProperties.Containers.defaults(), null);
        ContainerAllowList allowList = new ContainerAllowList(props);
        QueryRunner queryRunner = Mockito.mock(QueryRunner.class);
        Mockito.when(queryRunner.allowedContainers()).thenReturn(allowList.names());
        return new WorkloadEngine(
            database, allowList, queryRunner,
            Mockito.mock(BulkRunner.class), Mockito.mock(VectorSearchRunner.class),
            Mockito.mock(EmbeddingGenerator.class), new WorkloadVerifier(),
            new DomainInvariantChecker(), new WorkloadRegistry());
    }

    private static CosmosException cosmosError(int statusCode) {
        CosmosException e = Mockito.mock(CosmosException.class);
        Mockito.when(e.getStatusCode()).thenReturn(statusCode);
        return e;
    }

    @Test
    void retriesTransientConflictThenSucceeds() {
        CosmosItemResponse<Object> resp = successResponse();
        CosmosException conflict = cosmosError(409);
        AtomicInteger attempts = new AtomicInteger();
        // First two subscriptions 409 (the racing losers), third lands as a replace and succeeds.
        Mono<CosmosItemResponse<Object>> flaky =
            Mono.defer(() -> attempts.getAndIncrement() < 2 ? Mono.error(conflict) : Mono.just(resp));

        var result = engineReturning(flaky).executeCartUpsert(CART_STEP).block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(attempts.get()).isEqualTo(3); // 2 conflicts + 1 success
    }

    @Test
    void surfacesConflictThatOutlivesTheRetryBudget() {
        CosmosException conflict = cosmosError(409);
        AtomicInteger attempts = new AtomicInteger();
        Mono<CosmosItemResponse<Object>> alwaysConflict =
            Mono.defer(() -> { attempts.incrementAndGet(); return Mono.error(conflict); });

        assertThatThrownBy(() -> engineReturning(alwaysConflict).executeCartUpsert(CART_STEP).block(Duration.ofSeconds(5)))
            .isInstanceOf(CosmosException.class)
            .satisfies(e -> assertThat(((CosmosException) e).getStatusCode()).isEqualTo(409));
        // 1 initial attempt + CART_UPSERT_CONFLICT_MAX_RETRIES (3) retries; the original 409 is
        // propagated (not a RetryExhaustedException) so the op-error counter sees the real failure.
        assertThat(attempts.get()).isEqualTo(4);
    }

    @Test
    void doesNotRetryNonConflictErrors() {
        CosmosException serverError = cosmosError(500);
        AtomicInteger attempts = new AtomicInteger();
        Mono<CosmosItemResponse<Object>> alwaysFail =
            Mono.defer(() -> { attempts.incrementAndGet(); return Mono.error(serverError); });

        assertThatThrownBy(() -> engineReturning(alwaysFail).executeCartUpsert(CART_STEP).block(Duration.ofSeconds(5)))
            .isInstanceOf(CosmosException.class)
            .satisfies(e -> assertThat(((CosmosException) e).getStatusCode()).isEqualTo(500));
        assertThat(attempts.get()).isEqualTo(1); // no retry on a non-409
    }

    @SuppressWarnings("unchecked")
    private static CosmosItemResponse<Object> successResponse() {
        CosmosItemResponse<Object> resp = Mockito.mock(CosmosItemResponse.class);
        Mockito.when(resp.getStatusCode()).thenReturn(200);
        Mockito.when(resp.getRequestCharge()).thenReturn(1.0);
        return resp;
    }
}
