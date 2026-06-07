package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.CosmosContainerResponse;
import com.azure.cosmos.models.CosmosVectorDataType;
import com.azure.cosmos.models.CosmosVectorDistanceFunction;
import com.azure.cosmos.models.CosmosVectorEmbedding;
import com.azure.cosmos.models.CosmosVectorEmbeddingPolicy;
import com.azure.cosmos.models.CosmosVectorIndexSpec;
import com.azure.cosmos.models.CosmosVectorIndexType;
import com.azure.cosmos.models.ExcludedPath;
import com.azure.cosmos.models.IncludedPath;
import com.azure.cosmos.models.IndexingPolicy;
import com.azure.cosmos.models.PartitionKeyDefinition;
import com.azure.cosmos.models.PartitionKeyDefinitionVersion;
import com.azure.cosmos.models.PartitionKind;
import com.chronomart.config.ChronomartProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Idempotent container provisioning that runs once on startup. This is the only place
 * containers should ever be created/altered by ChronoMart.
 *
 * <p>What this does that the cosmoshell {@code .csh} seed scripts cannot:
 * <ul>
 *   <li><strong>Repairs the {@code Cart} container's default TTL</strong> — cosmoshell's
 *       {@code mkcon} doesn't expose a TTL flag, so a pre-seeded Cart container has
 *       {@code defaultTimeToLive=null} and per-item {@code ttl} fields are ignored.
 *       We read, compare, and {@code replace} container properties to install 7-day TTL.</li>
 *   <li><strong>Creates {@code ProductVectors}</strong> with the documented embedding policy
 *       (1024-dim cosine, mxbai-embed-large) + DiskANN vector index. The seed scripts
 *       can't express vector policy either.</li>
 *   <li>All other containers are no-ops: they're already present from the seed scripts.
 *       The {@code createContainerIfNotExists} call is a safety net for fresh runs against
 *       a non-seeded emulator.</li>
 * </ul>
 */
@Component
public class ContainerInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ContainerInitializer.class);
    private static final int CART_DEFAULT_TTL_SECONDS = 7 * 24 * 3600;
    /**
     * Embedding dimensionality for {@code ProductVectors}. Single source of truth — read
     * from {@link com.chronomart.repo.VectorSearchRunner} when validating request vectors
     * so the two paths cannot drift.
     */
    public static final int VECTOR_DIMENSIONS = 1024;
    /**
     * Distance function for {@code ProductVectors}. Affects how callers should interpret
     * the {@code score} field in {@code VectorSearchResponse}.
     */
    public static final CosmosVectorDistanceFunction VECTOR_DISTANCE_FUNCTION =
        CosmosVectorDistanceFunction.COSINE;
    private static final String VECTOR_EMBEDDING_PATH = "/embedding";
    /**
     * Bound the {@code createContainer(...)} block so a hanging emulator response
     * doesn't park the {@link ApplicationRunner} thread forever. The HPK 3-level
     * incident (E22P02 from the Java SDK on bodies the REST endpoint accepts)
     * telegraphed that DiskANN container creation is in the same wire-format risk
     * bucket — protect the boot path.
     */
    private static final Duration VECTOR_CREATE_TIMEOUT = Duration.ofSeconds(30);

    private final CosmosAsyncClient client;
    private final CosmosAsyncDatabase database;
    private final ChronomartProperties props;
    private final VectorContainerStatus vectorStatus;

    public ContainerInitializer(CosmosAsyncClient client, CosmosAsyncDatabase database,
                                ChronomartProperties props, VectorContainerStatus vectorStatus) {
        this.client = client;
        this.database = database;
        this.props = props;
        this.vectorStatus = vectorStatus;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.containerInit().enabled()) {
            log.info("Container init disabled (chronomart.cosmos.container-init.enabled=false), skipping");
            return;
        }
        log.info("Provisioning ChronoMart schema database={}", props.database());

        // Database itself
        client.createDatabaseIfNotExists(props.database()).block();
        log.info("  database '{}' ready", props.database());

        // Single-PK containers — all already present from seed; create-if-not-exists is the safety net.
        ensureSimplePk(props.containers().sellers(), "/id");
        ensureSimplePk(props.containers().products(), "/sellerId");
        ensureSimplePk(props.containers().inventory(), "/sellerId");
        ensureSimplePk(props.containers().customers(), "/id");
        ensureSimplePk(props.containers().reviews(), "/productId");
        ensureSimplePk(props.containers().changeFeedLease(), "/id");

        // Cart — single PK + 7-day default TTL (NOT settable from cosmoshell)
        ensureCartContainer(props.containers().cart());

        // Hierarchical-PK containers — present from seed. Targeting 2-level HPK on the
        // vNext emulator: the cosmoshell seed and the Java SDK's createContainer both
        // reliably create /sellerId,/categoryId (resp. /customerId,/yearMonth). The
        // contract docs target a 3rd /id leaf for routed point reads, but the vNext
        // emulator's createContainer fails with a Postgres E22P02 InternalServerError on
        // 3-level HPK from the Java SDK (the same shape via direct REST POST succeeds,
        // suggesting a SDK<->emulator wire-format mismatch). Tracked as a deferred goal;
        // once vNext supports the 3-level Java create — or once we move to a real Cosmos
        // account — flip these lists to add "/id" and ContainerInitializer will recreate
        // (since PK definition is immutable, that path requires drop + recreate).
        ensureHpk(props.containers().productsHpk(), List.of("/sellerId", "/categoryId"));
        ensureHpk(props.containers().orders(), List.of("/customerId", "/yearMonth"));

        // Vector container — only place vector policy can be set; gated by feature flag.
        if (props.containerInit().createVectorContainer()) {
            ensureVectorContainer(props.containers().productVectors());
        } else {
            log.info("  skipping {} (chronomart.cosmos.container-init.create-vector-container=false)",
                props.containers().productVectors());
        }

        log.info("Container provisioning complete.");
    }

    private void ensureSimplePk(String name, String pkPath) {
        PartitionKeyDefinition pk = new PartitionKeyDefinition().setPaths(List.of(pkPath));
        CosmosContainerProperties desired = new CosmosContainerProperties(name, pk);
        database.createContainerIfNotExists(desired).block();
        log.info("  container '{}' ready (pk={})", name, pkPath);
    }

    private void ensureHpk(String name, List<String> pkPaths) {
        PartitionKeyDefinition pk = new PartitionKeyDefinition()
            .setPaths(pkPaths)
            .setKind(PartitionKind.MULTI_HASH)
            .setVersion(PartitionKeyDefinitionVersion.V2);
        CosmosContainerProperties desired = new CosmosContainerProperties(name, pk);
        database.createContainerIfNotExists(desired).block();
        log.info("  container '{}' ready (hpk={})", name, pkPaths);
    }

    /**
     * Cart needs a per-item TTL story. cosmoshell can create the container but not set
     * {@code defaultTimeToLive}, so we read the current properties and {@code replace}
     * if the TTL doesn't match.
     */
    private void ensureCartContainer(String name) {
        PartitionKeyDefinition pk = new PartitionKeyDefinition().setPaths(List.of("/customerId"));
        CosmosContainerProperties desired = new CosmosContainerProperties(name, pk);
        desired.setDefaultTimeToLiveInSeconds(CART_DEFAULT_TTL_SECONDS);
        database.createContainerIfNotExists(desired).block();

        CosmosAsyncContainer container = database.getContainer(name);
        CosmosContainerResponse existing = container.read().block();
        if (existing == null) {
            throw new IllegalStateException("Failed to read just-created container: " + name);
        }
        CosmosContainerProperties current = existing.getProperties();
        Integer currentTtl = current.getDefaultTimeToLiveInSeconds();
        if (currentTtl == null || currentTtl != CART_DEFAULT_TTL_SECONDS) {
            log.info("  container '{}' has TTL={}, replacing with {}", name, currentTtl, CART_DEFAULT_TTL_SECONDS);
            current.setDefaultTimeToLiveInSeconds(CART_DEFAULT_TTL_SECONDS);
            container.replace(current).block();
        }
        log.info("  container '{}' ready (pk=/customerId, ttl={}s)", name, CART_DEFAULT_TTL_SECONDS);
    }

    /**
     * Provision a vector-enabled container. The vector embedding policy + DiskANN index
     * can only be set on the container at creation time (cannot be altered later), so we
     * skip the recreate path if the container already exists — but we DO read the existing
     * policy and warn loudly if it diverges from what this code wants, so a constant change
     * (e.g. bumping {@link #VECTOR_DIMENSIONS} without a drop+recreate) becomes visible at
     * the next boot instead of producing confusing runtime errors.
     *
     * <p>On success this flips {@link VectorContainerStatus#markReady()}; otherwise the
     * status stays {@code notReady} and the capability manifest + vector controller honor
     * it (the harness must not lie about features it cannot deliver).
     */
    private void ensureVectorContainer(String name) {
        try {
            CosmosAsyncContainer existing = database.getContainer(name);
            CosmosContainerResponse readResp = existing.read().block(VECTOR_CREATE_TIMEOUT);
            if (readResp != null) {
                checkExistingVectorPolicy(name, readResp.getProperties());
                vectorStatus.markReady();
                log.info("  container '{}' already exists; vector search ready", name);
                return;
            }
        } catch (com.azure.cosmos.CosmosException ce) {
            if (ce.getStatusCode() != 404) {
                log.warn("  could not read vector container '{}': status={} — leaving vector search OFF",
                    name, ce.getStatusCode());
                return;
            }
            // fall through to create
        } catch (RuntimeException re) {
            log.warn("  could not read vector container '{}': {} — leaving vector search OFF",
                name, re.getMessage());
            return;
        }

        PartitionKeyDefinition pk = new PartitionKeyDefinition().setPaths(List.of("/productId"));

        CosmosVectorEmbedding embedding = new CosmosVectorEmbedding()
            .setPath(VECTOR_EMBEDDING_PATH)
            .setDataType(CosmosVectorDataType.FLOAT32)
            .setEmbeddingDimensions(VECTOR_DIMENSIONS)
            .setDistanceFunction(VECTOR_DISTANCE_FUNCTION);
        CosmosVectorEmbeddingPolicy vectorPolicy = new CosmosVectorEmbeddingPolicy();
        vectorPolicy.setCosmosVectorEmbeddings(List.of(embedding));

        // DiskANN index on /embedding; exclude it from the default range index (perf).
        CosmosVectorIndexSpec indexSpec = new CosmosVectorIndexSpec()
            .setPath(VECTOR_EMBEDDING_PATH)
            .setType(CosmosVectorIndexType.DISK_ANN.toString());
        IndexingPolicy indexingPolicy = new IndexingPolicy()
            .setIncludedPaths(List.of(new IncludedPath("/*")))
            .setExcludedPaths(List.of(new ExcludedPath("/embedding/*")))
            .setVectorIndexes(List.of(indexSpec));

        CosmosContainerProperties desired = new CosmosContainerProperties(name, pk);
        desired.setIndexingPolicy(indexingPolicy);
        desired.setVectorEmbeddingPolicy(vectorPolicy);

        try {
            database.createContainer(desired).block(VECTOR_CREATE_TIMEOUT);
            vectorStatus.markReady();
            log.info("  container '{}' created (vector dim={}, {}, DiskANN); vector search ready",
                name, VECTOR_DIMENSIONS, VECTOR_DISTANCE_FUNCTION);
        } catch (RuntimeException e) {
            log.warn("  could not provision vector container '{}': {} — leaving vector search OFF "
                + "(see /api/v1/_meta/diagnostics for the SDK call detail)",
                name, e.getMessage());
        }
    }

    /**
     * Loud comparison of an existing {@code ProductVectors} container's embedding policy
     * against the values this code would create. Container properties are immutable post-
     * creation, so we cannot auto-repair — but a warn keeps the operator from chasing
     * silent dimension-mismatch errors at query time.
     */
    private void checkExistingVectorPolicy(String name, CosmosContainerProperties existing) {
        CosmosVectorEmbeddingPolicy policy = existing.getVectorEmbeddingPolicy();
        if (policy == null || policy.getVectorEmbeddings() == null || policy.getVectorEmbeddings().isEmpty()) {
            log.warn("  container '{}' exists but has NO vector embedding policy — vector search will fail. "
                + "Drop and recreate the container to fix.", name);
            return;
        }
        CosmosVectorEmbedding actual = policy.getVectorEmbeddings().get(0);
        Integer actualDim = actual.getEmbeddingDimensions();
        if (actualDim == null || actualDim != VECTOR_DIMENSIONS) {
            log.warn("  container '{}' embedding dimensions={} but code expects {} — "
                + "search requests bound by VectorSearchRunner will reject mismatched vectors. "
                + "Drop and recreate the container to fix.", name, actualDim, VECTOR_DIMENSIONS);
        }
        if (actual.getDistanceFunction() != VECTOR_DISTANCE_FUNCTION) {
            log.warn("  container '{}' distance function={} but code expects {} — "
                + "score interpretation will be wrong. Drop and recreate the container to fix.",
                name, actual.getDistanceFunction(), VECTOR_DISTANCE_FUNCTION);
        }
        if (!VECTOR_EMBEDDING_PATH.equals(actual.getPath())) {
            log.warn("  container '{}' embedding path='{}' but code expects '{}' — "
                + "VectorSearchRunner targets the expected path and will miss the index. "
                + "Drop and recreate the container to fix.",
                name, actual.getPath(), VECTOR_EMBEDDING_PATH);
        }
    }
}
