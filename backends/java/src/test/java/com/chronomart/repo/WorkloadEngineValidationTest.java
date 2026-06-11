package com.chronomart.repo;

import com.chronomart.config.ChronomartProperties;
import com.chronomart.web.dto.WorkloadSpec;
import com.chronomart.web.dto.WorkloadStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Offline validation tests for {@link WorkloadEngine#start(WorkloadSpec)}.
 *
 * <p>We never call {@code start()} here (that would require a live Cosmos handle). Instead
 * we directly drive the validation rules so PR1 has unit coverage of the user-facing 400
 * surface — the same rules will keep applying when PR2 adds more ops, and regressions in
 * the validator should fail loud and fast without a live emulator.
 */
class WorkloadEngineValidationTest {

    private WorkloadEngine engine;

    @BeforeEach
    void setup() {
        ChronomartProperties props = new ChronomartProperties(
            null, null, null, null, false, null,
            ChronomartProperties.Containers.defaults(),
            null);
        ContainerAllowList allowList = new ContainerAllowList(props);
        // We never call queryRunner.run() in validation, but the engine ctor calls
        // queryRunner.allowedContainers() to assert wiring. A mock is fine.
        QueryRunner queryRunner = Mockito.mock(QueryRunner.class);
        Mockito.when(queryRunner.allowedContainers()).thenReturn(allowList.names());
        WorkloadRegistry registry = new WorkloadRegistry();
        engine = new WorkloadEngine(
            Mockito.mock(com.azure.cosmos.CosmosAsyncDatabase.class),
            allowList, queryRunner, registry);
    }

    @Test
    void rejectsExcessiveConcurrency() {
        WorkloadSpec spec = new WorkloadSpec("over-cap", 10, 65, 0,
            List.of(validPointReadStep()));
        assertThatThrownBy(() -> engine.start(spec))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("concurrency");
    }

    @Test
    void rejectsDisallowedContainer() {
        WorkloadStep bad = new WorkloadStep("pointRead", "Definitely-Not-A-Container", 1,
            Map.of("ids", List.of("x"), "partitionKeys", List.of("y")));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("bad-container", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not in the allow-list");
    }

    @Test
    void rejectsDuplicateStepKey() {
        WorkloadStep a = validPointReadStep();
        WorkloadStep b = validPointReadStep();
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("dup", 10, 1, 0, List.of(a, b))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate step");
    }

    @Test
    void rejectsUnknownOp() {
        WorkloadStep bad = new WorkloadStep("teleport", "Products", 1, Map.of());
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("unknown", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown op");
    }

    @Test
    void rejectsKnownButUnimplementedOpWithFriendlyMessage() {
        WorkloadStep bulk = new WorkloadStep("bulk", "Inventory", 1, Map.of());
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("future-op", 10, 1, 0, List.of(bulk))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not yet implemented");
    }

    @Test
    void rejectsPointReadWithoutIds() {
        WorkloadStep bad = new WorkloadStep("pointRead", "Products", 1,
            Map.of("partitionKeys", List.of("seller-001")));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("no-ids", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ids");
    }

    @Test
    void rejectsPointReadWithMismatchedPkLength() {
        WorkloadStep bad = new WorkloadStep("pointRead", "Products", 1,
            Map.of(
                "ids", List.of("a", "b", "c"),
                "partitionKeys", List.of("x", "y")));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("pk-mismatch", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("partitionKeys length");
    }

    @Test
    void rejectsQueryWithoutPartitionKeyOrCrossPartition() {
        WorkloadStep bad = new WorkloadStep("query", "Products", 1,
            Map.of("query", "SELECT * FROM c"));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("no-pk", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("partitionKey")
            .hasMessageContaining("enableCrossPartition");
    }

    @Test
    void rejectsCartUpsertOnWrongContainer() {
        WorkloadStep bad = new WorkloadStep("cartUpsert", "Products", 1,
            Map.of("customerIds", List.of("c1")));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("wrong-target", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Cart container");
    }

    private static WorkloadStep validPointReadStep() {
        return new WorkloadStep("pointRead", "Products", 1,
            Map.of(
                "ids", List.of("prod-001"),
                "partitionKeys", List.of("seller-001")));
    }
}
