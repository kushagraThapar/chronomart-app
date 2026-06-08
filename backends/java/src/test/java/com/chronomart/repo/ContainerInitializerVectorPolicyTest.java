package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.CosmosVectorDataType;
import com.azure.cosmos.models.CosmosVectorDistanceFunction;
import com.azure.cosmos.models.CosmosVectorEmbedding;
import com.azure.cosmos.models.CosmosVectorEmbeddingPolicy;
import com.azure.cosmos.models.CosmosVectorIndexSpec;
import com.azure.cosmos.models.CosmosVectorIndexType;
import com.azure.cosmos.models.IndexingPolicy;
import com.azure.cosmos.models.PartitionKeyDefinition;
import com.chronomart.config.ChronomartProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct coverage of {@link ContainerInitializer#checkExistingVectorPolicy(String,
 * CosmosContainerProperties)} — the predicate that gates {@code vectorStatus.markReady()}
 * on both the "container already existed" branch and the "we just created it" branch.
 *
 * <p>This is the exact bug class the Copilot review on PR8 caught: the predicate
 * existed (with informative WARN logs) but {@code markReady()} was called unconditionally
 * because the predicate's verdict was thrown away. Tests pin down the contract:
 * <ul>
 *   <li>no vector index on /embedding → REJECT (search would full-scan)</li>
 *   <li>index present, embedding policy missing → ACCEPT with WARN (vNext gap; the
 *       index alone is enough for queries to work)</li>
 *   <li>index present, policy present and matching → ACCEPT (happy real-Cosmos path)</li>
 *   <li>index present, policy mismatched on any axis (dim, distance, path) → REJECT</li>
 * </ul>
 */
class ContainerInitializerVectorPolicyTest {

    private static final String NAME = "ProductVectors";

    private ContainerInitializer subject;

    @BeforeEach
    void setUp() {
        CosmosAsyncClient client = Mockito.mock(CosmosAsyncClient.class);
        CosmosAsyncDatabase db = Mockito.mock(CosmosAsyncDatabase.class);
        ChronomartProperties props = new ChronomartProperties(null, null, null, null, false, null, null, null);
        VectorContainerStatus status = new VectorContainerStatus();
        subject = new ContainerInitializer(client, db, props, status);
    }

    @Test
    void indexAndPolicyBothPresentAndMatchingIsReady() {
        assertTrue(subject.checkExistingVectorPolicy(NAME, propsWith(vectorIndex(), compatibleEmbedding())));
    }

    @Test
    void indexPresentButPolicyMissingIsReadyWithWarn() {
        // vNext-observed scenario: emulator strips embedding policy during create, but the
        // index registration persists and queries return correct results. The harness must
        // mark ready so the capability manifest matches reality.
        assertTrue(subject.checkExistingVectorPolicy(NAME, propsWith(vectorIndex(), null)));
    }

    @Test
    void noVectorIndexIsNotReady() {
        assertFalse(subject.checkExistingVectorPolicy(NAME, propsWith(null, null)));
    }

    @Test
    void noVectorIndexEvenWithCorrectPolicyIsNotReady() {
        // Defensive: a real-Cosmos quirk where policy was set but index was dropped
        // (unlikely, but the index is the authoritative read).
        assertFalse(subject.checkExistingVectorPolicy(NAME, propsWith(null, compatibleEmbedding())));
    }

    @Test
    void policyWithMismatchedDimensionsIsNotReady() {
        CosmosVectorEmbedding tooSmall = compatibleEmbedding().setEmbeddingDimensions(768);
        assertFalse(subject.checkExistingVectorPolicy(NAME, propsWith(vectorIndex(), tooSmall)));
    }

    @Test
    void policyWithMismatchedDistanceFunctionIsNotReady() {
        CosmosVectorEmbedding wrongFn = compatibleEmbedding()
            .setDistanceFunction(CosmosVectorDistanceFunction.DOT_PRODUCT);
        assertFalse(subject.checkExistingVectorPolicy(NAME, propsWith(vectorIndex(), wrongFn)));
    }

    @Test
    void policyWithMismatchedEmbeddingPathIsNotReady() {
        CosmosVectorEmbedding wrongPath = compatibleEmbedding().setPath("/notEmbedding");
        assertFalse(subject.checkExistingVectorPolicy(NAME, propsWith(vectorIndex(), wrongPath)));
    }

    private static CosmosVectorEmbedding compatibleEmbedding() {
        return new CosmosVectorEmbedding()
            .setPath("/embedding")
            .setDataType(CosmosVectorDataType.FLOAT32)
            .setEmbeddingDimensions(ContainerInitializer.VECTOR_DIMENSIONS)
            .setDistanceFunction(ContainerInitializer.VECTOR_DISTANCE_FUNCTION);
    }

    private static CosmosVectorIndexSpec vectorIndex() {
        return new CosmosVectorIndexSpec()
            .setPath("/embedding")
            .setType(CosmosVectorIndexType.DISK_ANN.toString());
    }

    private static CosmosContainerProperties propsWith(CosmosVectorIndexSpec index,
                                                       CosmosVectorEmbedding embedding) {
        CosmosContainerProperties props = new CosmosContainerProperties(
            NAME,
            new PartitionKeyDefinition().setPaths(List.of("/productId")));
        IndexingPolicy indexingPolicy = new IndexingPolicy();
        if (index != null) {
            indexingPolicy.setVectorIndexes(List.of(index));
        }
        props.setIndexingPolicy(indexingPolicy);
        if (embedding != null) {
            CosmosVectorEmbeddingPolicy policy = new CosmosVectorEmbeddingPolicy();
            policy.setCosmosVectorEmbeddings(List.of(embedding));
            props.setVectorEmbeddingPolicy(policy);
        }
        return props;
    }
}
