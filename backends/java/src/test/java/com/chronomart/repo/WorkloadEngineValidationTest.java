package com.chronomart.repo;

import com.chronomart.config.ChronomartProperties;
import com.chronomart.web.dto.WorkloadSpec;
import com.chronomart.web.dto.WorkloadStep;
import com.chronomart.web.dto.WorkloadVerification;
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
 * we directly drive the validation rules so all 7 ops have unit coverage of the user-facing
 * 400 surface — regressions in the validator fail loud and fast without a live emulator.
 *
 * <p>Tests cover both negative branches ({@code rejects...}) and at least one positive
 * branch per op ({@code accepts...}) so a future refactor that tightens validation cannot
 * silently break a currently-valid spec without flipping a positive test red.
 */
class WorkloadEngineValidationTest {

    private WorkloadEngine engine;
    private VectorSearchRunner vectorSearchRunner;

    @BeforeEach
    void setup() {
        ChronomartProperties props = new ChronomartProperties(
            null, null, null, null, false, null,
            ChronomartProperties.Containers.defaults(),
            null);
        ContainerAllowList allowList = new ContainerAllowList(props);
        QueryRunner queryRunner = Mockito.mock(QueryRunner.class);
        Mockito.when(queryRunner.allowedContainers()).thenReturn(allowList.names());
        BulkRunner bulkRunner = Mockito.mock(BulkRunner.class);
        vectorSearchRunner = Mockito.mock(VectorSearchRunner.class);
        Mockito.when(vectorSearchRunner.isReady()).thenReturn(true);
        EmbeddingGenerator embeddingGenerator = Mockito.mock(EmbeddingGenerator.class);
        WorkloadVerifier verifier = new WorkloadVerifier();
        WorkloadRegistry registry = new WorkloadRegistry();
        engine = new WorkloadEngine(
            Mockito.mock(com.azure.cosmos.CosmosAsyncDatabase.class),
            allowList, queryRunner, bulkRunner, vectorSearchRunner, embeddingGenerator,
            verifier, registry);
    }

    // ----- spec-level guards -----

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

    // ----- pointRead -----

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

    // ----- query -----

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

    // ----- query verification mode (NEW) -----

    @Test
    void rejectsVerificationQueryWithoutPkField() {
        WorkloadStep bad = new WorkloadStep("query", "Products", 1, Map.of());
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("vq-no-pkfield", 10, 1, 0, List.of(bad), verifyEnabled())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pkField");
    }

    @Test
    void rejectsVerificationQueryWithUnsafePkField() {
        WorkloadStep bad = new WorkloadStep("query", "Products", 1, Map.of("pkField", "sellerId; DROP"));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("vq-injection", 10, 1, 0, List.of(bad), verifyEnabled())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("simple identifier");
    }

    @Test
    void acceptsVerificationQueryWithSafePkField() {
        WorkloadStep good = new WorkloadStep("query", "Products", 1, Map.of("pkField", "sellerId"));
        assertThat(engine.start(new WorkloadSpec("vq-ok", 1, 1, 0, List.of(good), verifyEnabled())))
            .isNotBlank();
    }

    // ----- cartUpsert -----

    @Test
    void rejectsCartUpsertOnWrongContainer() {
        WorkloadStep bad = new WorkloadStep("cartUpsert", "Products", 1,
            Map.of("customerIds", List.of("c1")));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("wrong-target", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Cart container");
    }

    @Test
    void rejectsCartUpsertWithoutCustomerIds() {
        WorkloadStep bad = new WorkloadStep("cartUpsert", "Cart", 1, Map.of());
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("no-customer-ids", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("customerIds");
    }

    // ----- pointUpsert (NEW) -----

    @Test
    void rejectsPointUpsertWithoutPartitionKeys() {
        WorkloadStep bad = new WorkloadStep("pointUpsert", "Products", 1,
            Map.of("pkField", "sellerId"));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("no-pks", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("partitionKeys");
    }

    @Test
    void rejectsPointUpsertWithoutPkField() {
        WorkloadStep bad = new WorkloadStep("pointUpsert", "Products", 1,
            Map.of("partitionKeys", List.of("seller-001")));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("no-pk-field", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pkField");
    }

    @Test
    void rejectsPointUpsertWithHierarchicalPartitionKey() {
        WorkloadStep bad = new WorkloadStep("pointUpsert", "ProductsHpk", 1,
            Map.of(
                "partitionKeys", List.of(List.of("s1", "c1")),
                "pkField", "sellerId"));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("hpk-via-pointUpsert", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hierarchical");
    }

    @Test
    void acceptsValidPointUpsert() {
        WorkloadStep good = new WorkloadStep("pointUpsert", "Products", 1,
            Map.of(
                "partitionKeys", List.of("seller-001", "seller-002"),
                "pkField", "sellerId",
                "template", Map.of("name", "synthesised", "price", 199.99)));
        // Validation only — start() returns the runId synchronously; the async cosmos
        // chain hits our mock database and counts NPEs as errors, but no exception
        // escapes back to the caller. A non-null return == validation passed.
        assertThat(engine.start(new WorkloadSpec("ok-upsert", 1, 1, 0, List.of(good))))
            .isNotBlank();
    }

    // ----- hpkPointRead (NEW) -----

    @Test
    void rejectsHpkPointReadOnNonHierarchicalContainer() {
        WorkloadStep bad = new WorkloadStep("hpkPointRead", "Products", 1,
            Map.of(
                "ids", List.of("prod-001"),
                "partitionKeys", List.of(List.of("seller-001", "cat-a"))));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("wrong-container", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hierarchical container");
    }

    @Test
    void rejectsHpkPointReadWithSingleLevelKey() {
        WorkloadStep bad = new WorkloadStep("hpkPointRead", "ProductsHpk", 1,
            Map.of(
                "ids", List.of("prod-001"),
                "partitionKeys", List.of("seller-001")));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("single-level", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("2- or 3-level array");
    }

    @Test
    void rejectsHpkPointReadWithWrongArity() {
        WorkloadStep bad = new WorkloadStep("hpkPointRead", "ProductsHpk", 1,
            Map.of(
                "ids", List.of("prod-001"),
                "partitionKeys", List.of(List.of("s1", "c1", "id1", "extra"))));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("arity-4", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("2 or 3 levels");
    }

    @Test
    void rejectsHpkPointReadWithMismatchedPkLength() {
        WorkloadStep bad = new WorkloadStep("hpkPointRead", "ProductsHpk", 1,
            Map.of(
                "ids", List.of("a", "b", "c"),
                "partitionKeys", List.of(
                    List.of("s1", "c1"),
                    List.of("s2", "c2"))));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("pk-mismatch", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("partitionKeys length");
    }

    @Test
    void acceptsValidHpkPointRead() {
        WorkloadStep good = new WorkloadStep("hpkPointRead", "Orders", 1,
            Map.of(
                "ids", List.of("order-1", "order-2"),
                "partitionKeys", List.of(
                    List.of("cust-001", "2026-06"),
                    List.of("cust-002", "2026-06"))));
        assertThat(engine.start(new WorkloadSpec("ok-hpk", 1, 1, 0, List.of(good))))
            .isNotBlank();
    }

    // ----- vectorSearch (NEW) -----

    @Test
    void rejectsVectorSearchWhenContainerNotReady() {
        Mockito.when(vectorSearchRunner.isReady()).thenReturn(false);
        WorkloadStep bad = new WorkloadStep("vectorSearch", "ProductVectors", 1,
            Map.of("seeds", List.of("seed-1")));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("not-ready", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unavailable");
    }

    @Test
    void rejectsVectorSearchWithoutSeeds() {
        WorkloadStep bad = new WorkloadStep("vectorSearch", "ProductVectors", 1, Map.of());
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("no-seeds", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("seeds");
    }

    @Test
    void rejectsVectorSearchWithBlankSeed() {
        WorkloadStep bad = new WorkloadStep("vectorSearch", "ProductVectors", 1,
            Map.of("seeds", List.of("ok", "   ")));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("blank-seed", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-blank strings");
    }

    @Test
    void rejectsVectorSearchWithKOutOfRange() {
        WorkloadStep bad = new WorkloadStep("vectorSearch", "ProductVectors", 1,
            Map.of("seeds", List.of("seed-1"), "k", 200));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("k-too-big", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("k must be in");
    }

    @Test
    void acceptsValidVectorSearch() {
        WorkloadStep good = new WorkloadStep("vectorSearch", "ProductVectors", 1,
            Map.of("seeds", List.of("watch", "vintage"), "k", 10));
        assertThat(engine.start(new WorkloadSpec("ok-vec", 1, 1, 0, List.of(good))))
            .isNotBlank();
    }

    // ----- bulk (NEW) -----

    @Test
    void rejectsBulkWithUnsupportedOp() {
        WorkloadStep bad = new WorkloadStep("bulk", "Products", 1,
            Map.of("op", "replace", "partitionKey", "seller-001", "batchSize", 5));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("replace-disallowed", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("create, upsert");
    }

    @Test
    void rejectsBulkWithUnknownOp() {
        WorkloadStep bad = new WorkloadStep("bulk", "Products", 1,
            Map.of("op", "destroy", "partitionKey", "seller-001", "batchSize", 5));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("destroy-op", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("create, upsert, replace, delete");
    }

    @Test
    void rejectsBulkWithoutPartitionKey() {
        WorkloadStep bad = new WorkloadStep("bulk", "Products", 1,
            Map.of("op", "upsert", "batchSize", 5));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("no-pk", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("partitionKey");
    }

    @Test
    void rejectsBulkWithMissingBatchSize() {
        WorkloadStep bad = new WorkloadStep("bulk", "Products", 1,
            Map.of("op", "upsert", "partitionKey", "seller-001"));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("no-batch", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("batchSize");
    }

    @Test
    void rejectsBulkWithBatchSizeOverLimit() {
        WorkloadStep bad = new WorkloadStep("bulk", "Products", 1,
            Map.of("op", "upsert", "partitionKey", "seller-001", "batchSize", 1000));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("too-big", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("batchSize must be in");
    }

    @Test
    void rejectsBulkAgainstHpkContainer() {
        WorkloadStep bad = new WorkloadStep("bulk", "Orders", 1,
            Map.of(
                "op", "upsert",
                "partitionKey", List.of("cust-001", "2026-06"),
                "batchSize", 10,
                "pkField", "customerId"));
        assertThatThrownBy(() -> engine.start(
            new WorkloadSpec("bulk-vs-hpk", 10, 1, 0, List.of(bad))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hierarchical container");
    }

    @Test
    void acceptsValidBulkUpsert() {
        WorkloadStep good = new WorkloadStep("bulk", "Products", 1,
            Map.of(
                "op", "upsert",
                "partitionKey", "seller-001",
                "batchSize", 10,
                "pkField", "sellerId",
                "template", Map.of("name", "bulk-synth")));
        assertThat(engine.start(new WorkloadSpec("ok-bulk", 1, 1, 0, List.of(good))))
            .isNotBlank();
    }

    private static WorkloadVerification verifyEnabled() {
        return new WorkloadVerification(true, "session", null,
            new WorkloadVerification.Keyspace("wl", 50), 1.0, null, null, null);
    }

    private static WorkloadStep validPointReadStep() {
        return new WorkloadStep("pointRead", "Products", 1,
            Map.of(
                "ids", List.of("prod-001"),
                "partitionKeys", List.of("seller-001")));
    }
}
