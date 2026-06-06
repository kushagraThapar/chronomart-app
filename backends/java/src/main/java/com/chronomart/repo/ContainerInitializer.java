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
    private static final int VECTOR_DIMENSIONS = 1024;

    private final CosmosAsyncClient client;
    private final CosmosAsyncDatabase database;
    private final ChronomartProperties props;

    public ContainerInitializer(CosmosAsyncClient client, CosmosAsyncDatabase database,
                                ChronomartProperties props) {
        this.client = client;
        this.database = database;
        this.props = props;
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
     * skip this entirely if the container already exists.
     */
    private void ensureVectorContainer(String name) {
        try {
            CosmosAsyncContainer existing = database.getContainer(name);
            CosmosContainerResponse readResp = existing.read().block();
            if (readResp != null) {
                log.info("  container '{}' already exists; skipping vector-policy provisioning", name);
                return;
            }
        } catch (com.azure.cosmos.CosmosException ce) {
            if (ce.getStatusCode() != 404) {
                throw ce;
            }
            // fall through to create
        }

        PartitionKeyDefinition pk = new PartitionKeyDefinition().setPaths(List.of("/productId"));

        // 1024-dim cosine, mxbai-embed-large
        CosmosVectorEmbedding embedding = new CosmosVectorEmbedding()
            .setPath("/embedding")
            .setDataType(CosmosVectorDataType.FLOAT32)
            .setDimensions((long) VECTOR_DIMENSIONS)
            .setDistanceFunction(CosmosVectorDistanceFunction.COSINE);
        CosmosVectorEmbeddingPolicy vectorPolicy = new CosmosVectorEmbeddingPolicy();
        vectorPolicy.setCosmosVectorEmbeddings(List.of(embedding));

        // DiskANN index on /embedding; exclude it from the default range index (perf).
        CosmosVectorIndexSpec indexSpec = new CosmosVectorIndexSpec()
            .setPath("/embedding")
            .setType(CosmosVectorIndexType.DISK_ANN.toString());
        IndexingPolicy indexingPolicy = new IndexingPolicy()
            .setIncludedPaths(List.of(new IncludedPath("/*")))
            .setExcludedPaths(List.of(new ExcludedPath("/embedding/*")))
            .setVectorIndexes(List.of(indexSpec));

        CosmosContainerProperties desired = new CosmosContainerProperties(name, pk);
        desired.setIndexingPolicy(indexingPolicy);
        desired.setVectorEmbeddingPolicy(vectorPolicy);

        try {
            database.createContainer(desired).block();
            log.info("  container '{}' created (vector dim={}, cosine, DiskANN)", name, VECTOR_DIMENSIONS);
        } catch (RuntimeException e) {
            log.warn("  could not provision vector container '{}': {} — skipping (will retry next boot)",
                name, e.getMessage());
        }
    }
}
