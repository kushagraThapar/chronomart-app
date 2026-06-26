package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.PartitionKey;
import com.chronomart.config.ChronomartProperties;
import com.chronomart.web.dto.OpHistoryRecord;
import com.chronomart.web.dto.WorkloadSpec;
import com.chronomart.web.dto.WorkloadStep;
import com.chronomart.web.dto.WorkloadVerification;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * Pre-seed history-recording coverage for {@link WorkloadEngine#preSeedKeyspace}.
 *
 * <p>The verification engine pre-seeds its owned keyspace with one self-verifying write per key
 * before the workload starts. Those seed writes MUST land in the op-history artifact: the offline
 * {@link com.chronomart.oracle.OfflineHistoryAnalyzer} re-derives each key's {@code maxAllocatedSeq}
 * purely from the downloaded history, so if a key receives only reads during the run (no recorded
 * workload write) and the seed write is absent, the analyzer mis-flags the legitimate seed-value
 * read as a {@code PHANTOM_READ}. That false positive is non-deterministic (it only surfaces when a
 * key happens to get zero workload writes), which made it a flaky CI gate — these tests pin the
 * invariant that closes the gap.
 */
class WorkloadEnginePreSeedTest {

    private static final String CONTAINER = "Inventory";
    private static final int KEYSPACE_SIZE = 3;

    @SuppressWarnings("unchecked")
    private WorkloadEngine engineWithUpsert(CosmosAsyncDatabase database, Mono<CosmosItemResponse<Object>> upsertResult) {
        CosmosAsyncContainer container = Mockito.mock(CosmosAsyncContainer.class);
        Mockito.when(database.getContainer(CONTAINER)).thenReturn(container);
        Mockito.when(container.upsertItem(any(), any(PartitionKey.class), any(CosmosItemRequestOptions.class)))
            .thenReturn((Mono) upsertResult);

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

    private static final WorkloadVerification VERIFICATION = new WorkloadVerification(true, "session", null,
        new WorkloadVerification.Keyspace("wlverify", KEYSPACE_SIZE), 1.0, null, null, null);

    private static WorkloadEngine.WorkloadRunState verificationRunState(WorkloadVerificationState vstate) {
        WorkloadStep upsert = new WorkloadStep("pointUpsert", CONTAINER, 1,
            Map.of("partitionKeys", List.of("seller-001"), "pkField", "sellerId"));
        WorkloadSpec spec = new WorkloadSpec("seed-test", 1, 1, 0, List.of(upsert), VERIFICATION);
        return new WorkloadEngine.WorkloadRunState("seed-test", spec, Instant.now(), vstate);
    }

    private static WorkloadVerificationState verificationState() {
        return new WorkloadVerificationState("seed-test", VERIFICATION);
    }

    @Test
    void recordsOkSeedWritesInHistory() {
        @SuppressWarnings("unchecked")
        CosmosItemResponse<Object> resp = Mockito.mock(CosmosItemResponse.class);
        Mockito.when(resp.getStatusCode()).thenReturn(201);
        Mockito.when(resp.getRequestCharge()).thenReturn(1.0);
        CosmosAsyncDatabase database = Mockito.mock(CosmosAsyncDatabase.class);
        WorkloadEngine engine = engineWithUpsert(database, Mono.just(resp));

        WorkloadVerificationState vstate = verificationState();
        engine.preSeedKeyspace(verificationRunState(vstate), vstate).block(Duration.ofSeconds(5));

        List<OpHistoryRecord> history = vstate.history(0, 100);
        assertThat(history).hasSize(KEYSPACE_SIZE);
        assertThat(history).allSatisfy(r -> {
            assertThat(r.op()).isEqualTo("pointUpsert");
            assertThat(r.container()).isEqualTo(CONTAINER);
            assertThat(r.userIdx()).isEqualTo(-1);               // documented system-seed writer
            assertThat(r.outcome()).isEqualTo(OpHistoryRecord.OUTCOME_OK);
            assertThat(r.writeSeq()).isEqualTo(1L);              // first write to each key allocates seq 1
            assertThat(r.observedSeq()).isNull();
        });
        // Every seeded key now has maxAllocatedSeq >= 1, so a later seed-value read is not a phantom.
        assertThat(history).extracting(OpHistoryRecord::key).doesNotHaveDuplicates().hasSize(KEYSPACE_SIZE);
    }

    @Test
    void recordsErroredSeedWritesInHistory() {
        CosmosAsyncDatabase database = Mockito.mock(CosmosAsyncDatabase.class);
        WorkloadEngine engine = engineWithUpsert(database, Mono.error(new RuntimeException("seed boom")));

        WorkloadVerificationState vstate = verificationState();
        // Seed failures are swallowed (best-effort) so a flaky key never aborts the run.
        engine.preSeedKeyspace(verificationRunState(vstate), vstate).block(Duration.ofSeconds(5));

        List<OpHistoryRecord> history = vstate.history(0, 100);
        assertThat(history).hasSize(KEYSPACE_SIZE);
        assertThat(history).allSatisfy(r -> {
            assertThat(r.op()).isEqualTo("pointUpsert");
            assertThat(r.userIdx()).isEqualTo(-1);
            // An errored write may still have committed server-side, so its allocated seq is recorded
            // (OUTCOME_ERROR) and counts toward maxAllocatedSeq — preventing a later phantom false positive.
            assertThat(r.outcome()).isEqualTo(OpHistoryRecord.OUTCOME_ERROR);
            assertThat(r.writeSeq()).isEqualTo(1L);
        });
    }
}
