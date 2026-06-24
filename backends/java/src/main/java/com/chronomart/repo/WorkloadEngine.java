package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.CosmosVectorDistanceFunction;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.SqlParameter;
import com.azure.cosmos.models.SqlQuerySpec;
import com.chronomart.web.dto.AnomalySummary;
import com.chronomart.web.dto.BulkOperation;
import com.chronomart.web.dto.BulkRequest;
import com.chronomart.web.dto.QueryRequest;
import com.chronomart.web.dto.VectorMatch;
import com.chronomart.web.dto.VectorSearchRequest;
import com.chronomart.web.dto.WorkloadAnomaly;
import com.chronomart.web.dto.WorkloadOpStats;
import com.chronomart.web.dto.WorkloadProgress;
import com.chronomart.web.dto.WorkloadSpec;
import com.chronomart.web.dto.WorkloadStep;
import com.chronomart.web.dto.WorkloadTimePoint;
import jakarta.annotation.PreDestroy;
import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Composable workload runner. Executes a {@link WorkloadSpec} (weighted mix of Cosmos
 * operations) at the requested concurrency for the requested duration, recording per-op
 * throughput, RU, and latency percentiles into an {@link HdrHistogram} so the live
 * progress endpoint can return p50/p95/p99 without retaining every individual sample.
 *
 * <h2>Concurrency model</h2>
 * <p>Each "virtual user" is an independent reactor chain that fires one op at a time,
 * waits for its response (success or error), records the result, and immediately picks
 * its next op. {@code concurrency} virtual users means at most that many ops in-flight
 * at any moment — bounded backpressure on the Cosmos SDK without us having to manage a
 * semaphore manually. The op-picker uses thread-local weighted RNG so we don't contend
 * on a shared {@link java.util.Random}.
 *
 * <h2>Supported ops (v1)</h2>
 * <ul>
 *   <li><b>pointRead</b> — params {@code {ids: String[], partitionKeys: Object[]|Object}}
 *       (partitionKeys can be a single value applied to all ids, or a list parallel to
 *       ids). 404s are counted as errors so cache-miss bursts surface.</li>
 *   <li><b>query</b> — params {@code {query, parameters, partitionKey|enableCrossPartition,
 *       pageSize}}. Delegates to {@link QueryRunner} so safety rails (allow-list,
 *       cross-partition opt-in, parameter binding) are enforced identically.</li>
 *   <li><b>cartUpsert</b> — params {@code {customerIds: String[]}}. Builds a synthetic
 *       cart with 1..3 items (Map shape so we don't have to round-trip the strict
 *       {@code Cart} domain validators on every op) and upserts to {@code Cart}.</li>
 *   <li><b>pointUpsert</b> — params {@code {partitionKeys, pkField, idPrefix?, template?}}.
 *       Synthesises {@code {id: idPrefix+rand, [pkField]: pickedPk, ...template}} and
 *       upserts. Works against any allow-listed container; the caller picks the PK field
 *       name to keep the engine domain-agnostic (Products=sellerId, Cart=customerId, ...).</li>
 *   <li><b>hpkPointRead</b> — params {@code {ids: String[], partitionKeys: List<List<Object>>}}.
 *       Each {@code partitionKeys} entry must itself be a 2- or 3-level list (hierarchical).
 *       Validator-distinct from {@code pointRead} so a preset that meant to exercise HPK
 *       routing fails loudly rather than silently degrading to a single-level key.</li>
 *   <li><b>vectorSearch</b> — params {@code {seeds: String[], k?}}. Each op picks a seed,
 *       deterministically generates a 1024-FLOAT32 unit vector via {@link EmbeddingGenerator},
 *       and runs {@link VectorSearchRunner#search} with TOP-K (default 10). Container MUST
 *       be vector-enabled (gated on {@link VectorContainerStatus}).</li>
 *   <li><b>bulk</b> — params {@code {op, partitionKey, batchSize, idPrefix?, template?}}.
 *       Each op execution = one bulk request with {@code batchSize} items, so the ops/sec
 *       metric stays "bulk requests/sec" not "items/sec". Delegates to {@link BulkRunner}.</li>
 * </ul>
 *
 * <p>Every named op listed in {@link #KNOWN_OPS} is wired and executable; unknown ops
 * are rejected with a generic "unknown op" message at spec-validation time.
 *
 * <h2>State storage</h2>
 * <p>Each run holds a {@link WorkloadRunState} kept by {@link WorkloadRegistry}. A
 * single-threaded snapshotter ticks every second to push a {@link WorkloadTimePoint}
 * onto the time-series list (max ~1800 = the duration cap). When the engine bean is
 * destroyed, in-progress runs are cooperatively stopped.
 */
@Component
public class WorkloadEngine {

    private static final Logger LOG = LoggerFactory.getLogger(WorkloadEngine.class);

    /** Server-side ceiling on per-run concurrency. The vNext emulator on a laptop tops out
     *  around 32-64 concurrent ops before RU throttling dominates.
     *
     *  <p><b>Note:</b> the {@code @Max} on {@link WorkloadSpec#concurrency()} must be kept
     *  in sync with this constant (annotation values must be compile-time constants and
     *  cannot reference this field). */
    public static final int MAX_CONCURRENCY = 64;

    /** Every op named here is wired and executable. The validator uses this list both for
     *  the unknown-op error message and as the source of truth for the capability manifest. */
    public static final List<String> KNOWN_OPS = List.of(
        "pointRead", "pointUpsert", "query", "hpkPointRead", "vectorSearch", "bulk", "cartUpsert", "checkout"
    );

    /** Containers that {@code hpkPointRead} accepts. Other containers are allow-listed but
     *  not hierarchical, so an HPK-shaped read against them would silently degrade. */
    private static final Set<String> HPK_CONTAINERS = Set.of("ProductsHpk", "Orders");

    /** Bulk batch-size envelope. Upper bound matches {@code BulkRequest.operations} @Size(100). */
    private static final int BULK_MIN_BATCH = 1;
    private static final int BULK_MAX_BATCH = 100;

    /** vectorSearch top-K range — mirrors {@code VectorSearchRequest}. */
    private static final int VECTOR_MIN_K = 1;
    private static final int VECTOR_MAX_K = 100;

    /** Time-series snapshot interval. 1s matches the chart resolution in the UI. */
    private static final Duration SNAPSHOT_INTERVAL = Duration.ofSeconds(1);

    /** HdrHistogram tracks nanoseconds, 3 significant figures. Caps at ~1 hour per op so
     *  catastrophic timeouts are still recordable. ~50KB per histogram, well-bounded. */
    private static final long HISTOGRAM_MAX_NANOS = Duration.ofHours(1).toNanos();
    private static final int HISTOGRAM_SIGNIFICANT_DIGITS = 3;

    /** Max anomaly samples inlined into the progress payload; the full list pages via the endpoint. */
    private static final int MAX_SUMMARY_SAMPLES = 20;
    /** Concurrency used to pre-seed the owned keyspace before a verification run starts. */
    private static final int PRESEED_CONCURRENCY = 16;

    private final CosmosAsyncDatabase database;
    private final ContainerAllowList allowList;
    private final QueryRunner queryRunner;
    private final BulkRunner bulkRunner;
    private final VectorSearchRunner vectorSearchRunner;
    private final EmbeddingGenerator embeddingGenerator;
    private final WorkloadVerifier verifier;
    private final DomainInvariantChecker domainChecker;
    private final WorkloadRegistry registry;

    private final ScheduledExecutorService snapshotter =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "workload-snapshotter");
            t.setDaemon(true);
            return t;
        });

    public WorkloadEngine(CosmosAsyncDatabase database,
                          ContainerAllowList allowList,
                          QueryRunner queryRunner,
                          BulkRunner bulkRunner,
                          VectorSearchRunner vectorSearchRunner,
                          EmbeddingGenerator embeddingGenerator,
                          WorkloadVerifier verifier,
                          DomainInvariantChecker domainChecker,
                          WorkloadRegistry registry) {
        this.database = database;
        this.allowList = allowList;
        this.queryRunner = queryRunner;
        this.bulkRunner = bulkRunner;
        this.vectorSearchRunner = vectorSearchRunner;
        this.embeddingGenerator = embeddingGenerator;
        this.verifier = verifier;
        this.domainChecker = domainChecker;
        // Eagerly verify the allow-list is non-empty so a misconfigured bean fails fast
        // at startup rather than at the first workload run.
        if (this.queryRunner.allowedContainers().isEmpty()) {
            throw new IllegalStateException("QueryRunner has no allowed containers — check ContainerAllowList config");
        }
        this.registry = registry;
    }

    /**
     * Validate, register, and asynchronously launch a workload run.
     *
     * @return the assigned {@code runId} (caller polls {@code GET /workloads/{runId}})
     */
    public String start(WorkloadSpec spec) {
        validate(spec);
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        WorkloadVerificationState vstate =
            (spec.verification() != null && spec.verification().isEnabled())
                ? new WorkloadVerificationState(runId, spec.verification())
                : null;
        WorkloadRunState state = new WorkloadRunState(runId, spec, Instant.now(), vstate);
        registry.register(state);
        // A verification run pre-seeds its owned keyspace (one verified write per key) so reads
        // have data to check from the first tick; a 404 thereafter is real signal, not warm-up.
        Mono<Void> preSeed = vstate == null ? Mono.empty() : preSeedKeyspace(state, vstate);
        Mono<Void> driver = preSeed.then(driveRun(state))
            .doOnSuccess(v -> finalize(state, "COMPLETED", null))
            .doOnError(e -> finalize(state, "FAILED", e.getMessage()))
            .onErrorResume(e -> Mono.empty());
        ScheduledFuture<?> snap = snapshotter.scheduleAtFixedRate(
            () -> snapshot(state), SNAPSHOT_INTERVAL.toMillis(),
            SNAPSHOT_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        state.snapshotTask = snap;
        // Fire-and-forget on bounded-elastic so we don't tie up the snapshotter thread.
        driver.subscribeOn(Schedulers.boundedElastic()).subscribe();
        return runId;
    }

    /** Paged anomalies for a verification run. Returns {@code null} when the run is unknown, an
     *  empty list when the run had verification disabled or recorded nothing. */
    public List<WorkloadAnomaly> anomalies(String runId, int offset, int limit) {
        WorkloadRunState state = registry.get(runId);
        if (state == null) return null;
        return state.vstate == null ? List.of() : state.vstate.anomalies(offset, limit);
    }

    /** Cooperative stop. The run won't issue new ops once observed; in-flight ops
     *  complete normally. */
    public boolean stop(String runId) {
        WorkloadRunState state = registry.get(runId);
        if (state == null) return false;
        state.stopRequested.set(true);
        return true;
    }

    /** Build a {@link WorkloadProgress} snapshot from current run state. */
    public WorkloadProgress progress(String runId) {
        WorkloadRunState state = registry.get(runId);
        if (state == null) return null;
        return state.toProgress();
    }

    public List<WorkloadProgress> list() {
        return registry.all().stream().map(WorkloadRunState::toProgress).toList();
    }

    // ----- validation -----

    private void validate(WorkloadSpec spec) {
        if (spec.concurrency() > MAX_CONCURRENCY) {
            throw new IllegalArgumentException(
                "concurrency " + spec.concurrency() + " exceeds server cap of " + MAX_CONCURRENCY);
        }
        if (spec.steps().size() > 32) {
            throw new IllegalArgumentException("at most 32 steps per workload (got " + spec.steps().size() + ")");
        }
        boolean verifying = spec.verification() != null && spec.verification().isEnabled();
        Set<String> stepKeys = new HashSet<>();
        for (WorkloadStep s : spec.steps()) {
            allowList.requireAllowed(s.container());
            String key = s.op() + ":" + s.container();
            if (!stepKeys.add(key)) {
                throw new IllegalArgumentException(
                    "duplicate step " + key + " — merge weights into one step instead");
            }
            if (!KNOWN_OPS.contains(s.op())) {
                throw new IllegalArgumentException(
                    "unknown op '" + s.op() + "'. Known: " + KNOWN_OPS);
            }
            validateStepParams(s, verifying);
        }
    }

    private void validateStepParams(WorkloadStep s, boolean verifying) {
        Map<String, Object> p = s.params() == null ? Map.of() : s.params();
        switch (s.op()) {
            case "pointRead" -> {
                // In verification mode the engine draws keys + partition keys from the owned
                // keyspace, so caller-supplied ids/partitionKeys are not required.
                if (verifying) break;
                if (!(p.get("ids") instanceof List<?> ids) || ids.isEmpty()) {
                    throw new IllegalArgumentException("pointRead requires non-empty params.ids[]");
                }
                Object pks = p.get("partitionKeys");
                if (pks == null) {
                    throw new IllegalArgumentException("pointRead requires params.partitionKeys");
                }
                if (pks instanceof List<?> pkList && !pkList.isEmpty()) {
                    if (pkList.size() != 1 && pkList.size() != ids.size()) {
                        throw new IllegalArgumentException(
                            "pointRead partitionKeys length " + pkList.size()
                                + " must be 1 or match ids length " + ids.size());
                    }
                }
            }
            case "query" -> {
                if (verifying) {
                    // The engine generates the scoped query; it only needs to know the pk field.
                    // pkField is interpolated into the SQL property path (Cosmos can't parameterize
                    // a path), so require a simple identifier to keep it injection-safe.
                    if (!(p.get("pkField") instanceof String pkField) || pkField.isBlank()) {
                        throw new IllegalArgumentException(
                            "query in verification mode requires params.pkField (the partition-key field, e.g. 'sellerId')");
                    }
                    if (!pkField.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
                        throw new IllegalArgumentException(
                            "query params.pkField must be a simple identifier [A-Za-z_][A-Za-z0-9_]*, got '" + pkField + "'");
                    }
                    break;
                }
                if (!(p.get("query") instanceof String q) || q.isBlank()) {
                    throw new IllegalArgumentException("query requires non-blank params.query");
                }
                Boolean xPart = p.get("enableCrossPartition") instanceof Boolean b ? b : null;
                if (p.get("partitionKey") == null && !Boolean.TRUE.equals(xPart)) {
                    throw new IllegalArgumentException(
                        "query requires params.partitionKey or params.enableCrossPartition=true");
                }
            }
            case "cartUpsert" -> {
                if (!(p.get("customerIds") instanceof List<?> ids) || ids.isEmpty()) {
                    throw new IllegalArgumentException("cartUpsert requires non-empty params.customerIds[]");
                }
                if (!"Cart".equalsIgnoreCase(s.container())) {
                    throw new IllegalArgumentException(
                        "cartUpsert must target the Cart container (got " + s.container() + ")");
                }
            }
            case "pointUpsert" -> {
                // pkField (the doc field holding the partition key) is always required; in
                // verification mode the engine supplies the pk *values* from the owned keyspace,
                // so caller partitionKeys are not required.
                if (!(p.get("pkField") instanceof String pkField) || pkField.isBlank()) {
                    throw new IllegalArgumentException(
                        "pointUpsert requires params.pkField (the field name on the synthesised doc "
                            + "that holds the partition key value, e.g. 'sellerId' for Products)");
                }
                if (!verifying) {
                    if (!(p.get("partitionKeys") instanceof List<?> pks) || pks.isEmpty()) {
                        throw new IllegalArgumentException("pointUpsert requires non-empty params.partitionKeys[]");
                    }
                    // Reject hierarchical PK shapes here — pointUpsert sets a single field, so
                    // a list-of-lists PK couldn't be reflected onto the doc without invented
                    // schema. Use the bulk op for HPK writes.
                    for (Object pk : pks) {
                        if (pk instanceof List<?>) {
                            throw new IllegalArgumentException(
                                "pointUpsert does not support hierarchical partition keys "
                                    + "(level " + pkField + " is single-field). Use 'bulk' for HPK writes.");
                        }
                    }
                }
                if (p.get("template") != null && !(p.get("template") instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("pointUpsert params.template must be an object");
                }
            }
            case "hpkPointRead" -> {
                if (!HPK_CONTAINERS.contains(s.container())) {
                    throw new IllegalArgumentException(
                        "hpkPointRead must target a hierarchical container " + HPK_CONTAINERS
                            + " (got " + s.container() + "). Use 'pointRead' for single-level keys.");
                }
                if (!(p.get("ids") instanceof List<?> ids) || ids.isEmpty()) {
                    throw new IllegalArgumentException("hpkPointRead requires non-empty params.ids[]");
                }
                if (!(p.get("partitionKeys") instanceof List<?> pks) || pks.isEmpty()) {
                    throw new IllegalArgumentException(
                        "hpkPointRead requires non-empty params.partitionKeys[] (each entry a 2-3 level array)");
                }
                if (pks.size() != 1 && pks.size() != ids.size()) {
                    throw new IllegalArgumentException(
                        "hpkPointRead partitionKeys length " + pks.size()
                            + " must be 1 or match ids length " + ids.size());
                }
                for (int i = 0; i < pks.size(); i++) {
                    Object entry = pks.get(i);
                    if (!(entry instanceof List<?> levels)) {
                        throw new IllegalArgumentException(
                            "hpkPointRead partitionKeys[" + i + "] must be a 2- or 3-level array, "
                                + "got " + (entry == null ? "null" : entry.getClass().getSimpleName())
                                + " (use 'pointRead' for single-level keys)");
                    }
                    if (levels.size() < 2 || levels.size() > 3) {
                        throw new IllegalArgumentException(
                            "hpkPointRead partitionKeys[" + i + "] must have 2 or 3 levels, got " + levels.size());
                    }
                }
            }
            case "vectorSearch" -> {
                if (!vectorSearchRunner.isReady()) {
                    throw new IllegalArgumentException(
                        "vectorSearch is unavailable: " + s.container() + " was not provisioned at startup "
                            + "(see /_meta/diagnostics)");
                }
                if (!(p.get("seeds") instanceof List<?> seeds) || seeds.isEmpty()) {
                    throw new IllegalArgumentException(
                        "vectorSearch requires non-empty params.seeds[] (text strings hashed to query vectors)");
                }
                for (Object seed : seeds) {
                    if (!(seed instanceof String str) || str.isBlank()) {
                        throw new IllegalArgumentException(
                            "vectorSearch params.seeds[] entries must be non-blank strings");
                    }
                }
                Integer k = p.get("k") instanceof Number n ? n.intValue() : null;
                if (k != null && (k < VECTOR_MIN_K || k > VECTOR_MAX_K)) {
                    throw new IllegalArgumentException(
                        "vectorSearch params.k must be in [" + VECTOR_MIN_K + ", " + VECTOR_MAX_K + "], got " + k);
                }
            }
            case "bulk" -> {
                String op = p.get("op") instanceof String str ? str : null;
                if (op == null || !Set.of("create", "upsert", "replace", "delete").contains(op)) {
                    throw new IllegalArgumentException(
                        "bulk requires params.op in {create, upsert, replace, delete}, got " + op);
                }
                if ("replace".equals(op) || "delete".equals(op)) {
                    // These need stable existing ids — synthesising new ids per tick would 404.
                    // Use the standalone /bulk endpoint for those, not the workload runner.
                    throw new IllegalArgumentException(
                        "bulk workload op only supports {create, upsert} (got '" + op
                            + "' — replace/delete need pre-existing ids; use POST /bulk directly).");
                }
                if (HPK_CONTAINERS.contains(s.container())) {
                    // The runner synthesises docs with a single {pkField} field; HPK containers
                    // require all 2-3 levels present on every doc, so a synthesised single-field
                    // doc would 400 at Cosmos. Use POST /bulk directly for HPK writes.
                    throw new IllegalArgumentException(
                        "bulk workload op does not support hierarchical container " + s.container()
                            + " (synthesised docs only carry one PK field). Use POST /bulk directly.");
                }
                if (p.get("partitionKey") == null) {
                    throw new IllegalArgumentException(
                        "bulk requires params.partitionKey (single value applied to every item in the batch)");
                }
                Integer batchSize = p.get("batchSize") instanceof Number n ? n.intValue() : null;
                if (batchSize == null || batchSize < BULK_MIN_BATCH || batchSize > BULK_MAX_BATCH) {
                    throw new IllegalArgumentException(
                        "bulk params.batchSize must be in [" + BULK_MIN_BATCH + ", " + BULK_MAX_BATCH
                            + "], got " + batchSize);
                }
                if (p.get("template") != null && !(p.get("template") instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("bulk params.template must be an object");
                }
                if (p.get("pkField") != null && !(p.get("pkField") instanceof String)) {
                    throw new IllegalArgumentException("bulk params.pkField must be a string");
                }
            }
            case "checkout" -> {
                if (!"Orders".equalsIgnoreCase(s.container())) {
                    throw new IllegalArgumentException(
                        "checkout must target the Orders container (got " + s.container() + ")");
                }
                if (!(p.get("customerIds") instanceof List<?> ids) || ids.isEmpty()) {
                    throw new IllegalArgumentException("checkout requires non-empty params.customerIds[]");
                }
            }
            default -> throw new IllegalStateException("unreachable: validated op " + s.op());
        }
    }

    // ----- run driver -----

    /**
     * Pre-seed the owned keyspace: one verified write per key, so a verification run's reads have
     * data from the first tick. Container + pk-field are taken from the run's {@code pointUpsert}
     * step; with no write step there is nothing to seed (reads will surface UNEXPECTED_NOT_FOUND,
     * which is itself signal). Best-effort — individual seed failures are swallowed so a flaky
     * key doesn't abort the run.
     */
    private Mono<Void> preSeedKeyspace(WorkloadRunState state, WorkloadVerificationState vstate) {
        WorkloadStep writeStep = state.spec.steps().stream()
            .filter(s -> "pointUpsert".equals(s.op()))
            .findFirst().orElse(null);
        if (writeStep == null) return Mono.empty();
        String container = writeStep.container();
        String pkField = (String) writeStep.params().get("pkField");
        @SuppressWarnings("unchecked")
        Map<String, Object> template = writeStep.params().get("template") instanceof Map<?, ?> m
            ? (Map<String, Object>) m : null;
        CosmosAsyncContainer c = database.getContainer(container);
        return Flux.range(0, vstate.keyspaceSize())
            .flatMap(i -> {
                String key = vstate.keyAt(i);
                String pkValue = vstate.pkValueFor(key);
                // writer=-1: a system seed, not any virtual user's session write.
                Map<String, Object> doc = verifier.buildWriteDoc(vstate, container, key, pkField, pkValue, -1, template);
                long seq = VerifiedValue.seqOf(doc);
                return c.upsertItem(doc, new PartitionKey(pkValue), new CosmosItemRequestOptions())
                    .doOnSuccess(r -> vstate.ackWrite(container, key, seq))
                    .onErrorResume(e -> {
                        vstate.failWrite(container, key, seq);
                        return Mono.empty();
                    });
            }, PRESEED_CONCURRENCY)
            .then();
    }

    private Mono<Void> driveRun(WorkloadRunState state) {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(state.spec.durationSeconds()).toNanos();
        state.status = "RUNNING";
        return Flux.range(0, state.spec.concurrency())
            .flatMap(i -> userLoop(state, i, deadlineNanos), state.spec.concurrency())
            .then();
    }

    private Mono<Void> userLoop(WorkloadRunState state, int userIdx, long deadlineNanos) {
        return Mono.defer(() -> oneOp(state, userIdx))
            .repeat(() -> System.nanoTime() < deadlineNanos && !state.stopRequested.get())
            .then();
    }

    private Mono<Void> oneOp(WorkloadRunState state, int userIdx) {
        WorkloadStep step = pickStep(state.spec.steps(), state.totalWeight);
        StepStats stats = state.statsByStep.get(stepKey(step));
        long start = System.nanoTime();
        return execute(step, state, userIdx)
            .doOnNext(result -> stats.recordSuccess(System.nanoTime() - start, result.requestCharge()))
            .onErrorResume(e -> {
                stats.recordError(System.nanoTime() - start);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("workload op {} failed: {}", step.op(), e.toString());
                }
                return Mono.empty();
            })
            .then();
    }

    private static WorkloadStep pickStep(List<WorkloadStep> steps, int totalWeight) {
        if (steps.size() == 1) return steps.get(0);
        int pick = ThreadLocalRandom.current().nextInt(totalWeight);
        int acc = 0;
        for (WorkloadStep s : steps) {
            acc += s.weight();
            if (pick < acc) return s;
        }
        return steps.get(steps.size() - 1); // unreachable, defensive
    }

    // ----- op dispatch -----

    private Mono<OpResult> execute(WorkloadStep step, WorkloadRunState state, int userIdx) {
        return switch (step.op()) {
            case "pointRead" -> executePointRead(step, state, userIdx);
            case "query" -> executeQuery(step, state);
            case "cartUpsert" -> executeCartUpsert(step);
            case "pointUpsert" -> executePointUpsert(step, state, userIdx);
            case "hpkPointRead" -> executeHpkPointRead(step);
            case "vectorSearch" -> executeVectorSearch(step, state);
            case "bulk" -> executeBulk(step);
            case "checkout" -> executeCheckout(step, state);
            default -> Mono.error(new IllegalStateException("unsupported op: " + step.op()));
        };
    }

    private Mono<OpResult> executePointRead(WorkloadStep step, WorkloadRunState state, int userIdx) {
        WorkloadVerificationState v = state.vstate;
        if (v != null) {
            // Verification mode: draw a key (and its deterministic pk) from the owned keyspace and
            // check whatever comes back (L0 self-consistency + L1 temporal). A 404 is reported to
            // the verifier (not thrown) so it becomes an anomaly rather than an SDK error.
            String key = v.randomKey();
            String pkValue = v.pkValueFor(key);
            // Capture the linearizable lower bound BEFORE issuing the read: the highest *settled*
            // (non-concurrent, unambiguously-ordered) committed seq. A strong read must not return
            // an older value; using settledSeq avoids false positives from concurrent same-key writes.
            long settledFloorAtStart = v.settledSeq(step.container(), key);
            PartitionKey pk = new PartitionKey(pkValue);
            CosmosAsyncContainer container = database.getContainer(step.container());
            return container.readItem(key, pk, Map.class)
                .map(resp -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> doc = (Map<String, Object>) resp.getItem();
                    verifier.verifyRead(v, userIdx, "pointRead", step.container(), key, doc, settledFloorAtStart);
                    return new OpResult(resp.getRequestCharge(), resp.getStatusCode());
                })
                .onErrorResume(CosmosException.class, e -> {
                    if (e.getStatusCode() == 404) {
                        verifier.verifyRead(v, userIdx, "pointRead", step.container(), key, null, settledFloorAtStart);
                        return Mono.just(new OpResult(e.getRequestCharge(), 404));
                    }
                    return Mono.error(e);
                });
        }
        // ids is validated as List<?> in validateStepParams; elements may be any JSON scalar.
        // Use toString() so integer IDs like [1,2,3] don't ClassCastException at runtime.
        @SuppressWarnings("unchecked")
        List<?> rawIds = (List<?>) step.params().get("ids");
        Object pksRaw = step.params().get("partitionKeys");
        int i = ThreadLocalRandom.current().nextInt(rawIds.size());
        String id = rawIds.get(i).toString();
        Object pkRaw;
        if (pksRaw instanceof List<?> pks) {
            pkRaw = pks.size() == 1 ? pks.get(0) : pks.get(i);
        } else {
            pkRaw = pksRaw;
        }
        PartitionKey pk = allowList.parse(pkRaw);
        if (pk == null) return Mono.error(new IllegalArgumentException("pointRead pk resolved to null"));
        CosmosAsyncContainer container = database.getContainer(step.container());
        return container.readItem(id, pk, Object.class)
            .map(resp -> new OpResult(resp.getRequestCharge(), resp.getStatusCode()));
    }

    private Mono<OpResult> executeQuery(WorkloadStep step, WorkloadRunState state) {
        WorkloadVerificationState v = state.vstate;
        if (v != null) {
            // Verification mode: query one keyspace partition and assert every returned doc carries
            // that partition key (no cross-partition leak) and is a valid self-verifying value.
            // pkField is validated as a simple identifier, so interpolating it into the property
            // path (Cosmos can't parameterize a path) is safe.
            String pkField = (String) step.params().get("pkField");
            String pkValue = v.pkValueFor(v.randomKey());
            CosmosAsyncContainer c = database.getContainer(step.container());
            SqlQuerySpec spec = new SqlQuerySpec(
                "SELECT * FROM c WHERE c." + pkField + " = @pk",
                List.of(new SqlParameter("@pk", pkValue)));
            CosmosQueryRequestOptions opts = new CosmosQueryRequestOptions()
                .setPartitionKey(new PartitionKey(pkValue));
            return c.queryItems(spec, opts, Map.class)
                .byPage()
                .next()   // a single page is enough to check the predicate + values
                .map(page -> {
                    List<Map<String, Object>> docs = new ArrayList<>(page.getResults().size());
                    for (Object o : page.getResults()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> doc = (Map<String, Object>) o;
                        docs.add(doc);
                    }
                    verifier.verifyScopedQuery(v, "query", step.container(), pkField, pkValue, docs);
                    return new OpResult(page.getRequestCharge(), 200);
                })
                .defaultIfEmpty(new OpResult(0.0, 200));
        }
        Map<String, Object> p = step.params();
        Object rawParams = p.get("parameters");
        List<QueryRequest.Parameter> params = new ArrayList<>();
        if (rawParams instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Object n = m.get("name");
                    if (n instanceof String name) {
                        params.add(new QueryRequest.Parameter(name, m.get("value")));
                    }
                }
            }
        }
        Integer pageSize = p.get("pageSize") instanceof Number n ? n.intValue() : null;
        // Pattern-match to avoid ClassCastException when a string "true" is sent instead
        // of a JSON boolean true (both are valid JSON but only the latter deserialises as Boolean).
        Boolean xPart = p.get("enableCrossPartition") instanceof Boolean b ? b : null;
        QueryRequest req = new QueryRequest(
            step.container(),
            (String) p.get("query"),
            params,
            p.get("partitionKey"),
            pageSize,
            null,
            xPart,
            null
        );
        return queryRunner.run(req)
            .map(resp -> new OpResult(
                resp.requestCharge() == null ? 0.0 : resp.requestCharge(),
                200));
    }

    private Mono<OpResult> executeCartUpsert(WorkloadStep step) {
        // customerIds validated as List<?> in validateStepParams; toString() guards
        // against non-string JSON scalars (e.g. integer IDs) to avoid ClassCastException.
        @SuppressWarnings("unchecked")
        List<?> rawCustomerIds = (List<?>) step.params().get("customerIds");
        String customerId = rawCustomerIds.get(ThreadLocalRandom.current().nextInt(rawCustomerIds.size())).toString();
        Map<String, Object> doc = synthesizeCart(customerId);
        CosmosAsyncContainer container = database.getContainer(step.container());
        return container.upsertItem(doc, new PartitionKey(customerId), new CosmosItemRequestOptions())
            .map(resp -> new OpResult(resp.getRequestCharge(), resp.getStatusCode()));
    }

    private static Map<String, Object> synthesizeCart(String customerId) {
        int itemCount = 1 + ThreadLocalRandom.current().nextInt(3);
        List<Map<String, Object>> items = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("productId", "prod-" + String.format("%03d", 1 + ThreadLocalRandom.current().nextInt(30)));
            it.put("quantity", 1 + ThreadLocalRandom.current().nextInt(3));
            it.put("unitPrice", 100.0 + ThreadLocalRandom.current().nextInt(500));
            items.add(it);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", customerId);
        doc.put("customerId", customerId);
        doc.put("items", items);
        return doc;
    }

    private Mono<OpResult> executePointUpsert(WorkloadStep step, WorkloadRunState state, int userIdx) {
        WorkloadVerificationState v = state.vstate;
        if (v != null) {
            // Verification mode: pick a keyspace key, write a self-verifying value at the key's
            // next sequence, using the deterministic pk so reads address the same partition. The
            // write lifecycle (begin → ack/fail) feeds the reference model so reads can be checked.
            String pkField = (String) step.params().get("pkField");
            String key = v.randomKey();
            String pkValue = v.pkValueFor(key);
            @SuppressWarnings("unchecked")
            Map<String, Object> template = step.params().get("template") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
            // buildWriteDoc calls beginWrite (allocates seq + marks in-flight).
            Map<String, Object> doc = verifier.buildWriteDoc(v, step.container(), key, pkField, pkValue, userIdx, template);
            long seq = VerifiedValue.seqOf(doc);
            CosmosAsyncContainer container = database.getContainer(step.container());
            return container.upsertItem(doc, new PartitionKey(pkValue), new CosmosItemRequestOptions())
                .map(resp -> {
                    boolean settled = v.ackWrite(step.container(), key, seq);
                    // Only a settled (non-concurrent) write establishes this user's read-your-writes
                    // floor — a concurrent write's seq isn't a sound lower bound for later reads.
                    if (settled) {
                        v.bumpSessionFloor(userIdx, step.container(), key, seq, WorkloadVerificationState.SOURCE_WRITE);
                    }
                    return new OpResult(resp.getRequestCharge(), resp.getStatusCode());
                })
                .onErrorResume(e -> {
                    v.failWrite(step.container(), key, seq);
                    return Mono.error(e);
                });
        }
        // partitionKeys / pkField validated as non-empty/non-blank in validateStepParams.
        // Use List<?> + toString() so integer PK values don't ClassCastException at runtime
        // — see Copilot review on PR1 (cast on Jackson-deserialised List<String> erased to
        // Object at runtime).
        @SuppressWarnings("unchecked")
        List<?> rawPks = (List<?>) step.params().get("partitionKeys");
        String pkField = (String) step.params().get("pkField");
        Object pkRaw = rawPks.get(ThreadLocalRandom.current().nextInt(rawPks.size()));
        PartitionKey pk = allowList.parseRequired(pkRaw);
        String idPrefix = step.params().get("idPrefix") instanceof String s ? s : "wl-upsert-";
        Map<String, Object> doc = synthesizeUpsertDoc(idPrefix, pkField, pkRaw, step.params().get("template"));
        CosmosAsyncContainer container = database.getContainer(step.container());
        return container.upsertItem(doc, pk, new CosmosItemRequestOptions())
            .map(resp -> new OpResult(resp.getRequestCharge(), resp.getStatusCode()));
    }

    /**
     * Build a synthesised doc for {@code pointUpsert}: {@code {id, [pkField]: pkValue, ...template}}.
     * The id is fresh per call so each tick exercises the create-or-replace write path;
     * {@code template} fills in container-specific fields (e.g. {@code price}, {@code name}
     * for Products) that downstream readers may depend on.
     */
    private static Map<String, Object> synthesizeUpsertDoc(String idPrefix, String pkField, Object pkValue, Object template) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", idPrefix + Long.toHexString(ThreadLocalRandom.current().nextLong()));
        if (template instanceof Map<?, ?> rawTemplate) {
            for (Map.Entry<?, ?> e : rawTemplate.entrySet()) {
                if (e.getKey() instanceof String key) {
                    doc.put(key, e.getValue());
                }
            }
        }
        // Set pkField LAST so it always reflects the chosen PK (caller-supplied template
        // can't accidentally override it and route the write to the wrong partition).
        doc.put(pkField, pkValue);
        return doc;
    }

    private Mono<OpResult> executeHpkPointRead(WorkloadStep step) {
        // ids + partitionKeys (list-of-lists) validated in validateStepParams.
        @SuppressWarnings("unchecked")
        List<?> rawIds = (List<?>) step.params().get("ids");
        @SuppressWarnings("unchecked")
        List<?> rawPks = (List<?>) step.params().get("partitionKeys");
        int i = ThreadLocalRandom.current().nextInt(rawIds.size());
        String id = rawIds.get(i).toString();
        Object pkRaw = rawPks.size() == 1 ? rawPks.get(0) : rawPks.get(i);
        // parseRequired handles List<Object> via PartitionKeyBuilder; 2-3 level arity
        // already enforced by validator so a malformed pk here means a code bug.
        PartitionKey pk = allowList.parseRequired(pkRaw);
        CosmosAsyncContainer container = database.getContainer(step.container());
        return container.readItem(id, pk, Object.class)
            .map(resp -> new OpResult(resp.getRequestCharge(), resp.getStatusCode()));
    }

    private Mono<OpResult> executeVectorSearch(WorkloadStep step, WorkloadRunState state) {
        // seeds validated as non-empty List<String> in validateStepParams.
        @SuppressWarnings("unchecked")
        List<?> rawSeeds = (List<?>) step.params().get("seeds");
        String seed = (String) rawSeeds.get(ThreadLocalRandom.current().nextInt(rawSeeds.size()));
        Integer k = step.params().get("k") instanceof Number n ? n.intValue() : null;
        float[] vector = embeddingGenerator.embed(seed);
        VectorSearchRequest req = new VectorSearchRequest(step.container(), vector, k);
        WorkloadVerificationState v = state.vstate;
        return vectorSearchRunner.search(req)
            .map(resp -> {
                if (v != null) {
                    // ProductVectors uses COSINE (similarity), so most-similar-first means scores
                    // are non-increasing. A distance metric would be non-decreasing instead.
                    boolean descending = isSimilarityMetric(ContainerInitializer.VECTOR_DISTANCE_FUNCTION);
                    List<Double> scores = new ArrayList<>(resp.matches().size());
                    for (VectorMatch m : resp.matches()) {
                        scores.add(m.score());
                    }
                    verifier.verifyVectorOrder(v, "vectorSearch", step.container(), scores, descending);
                }
                return new OpResult(resp.requestCharge(), 200);
            });
    }

    /** Similarity metrics (COSINE, DOT_PRODUCT) rank higher-is-closer → scores descend;
     *  distance metrics (EUCLIDEAN) rank lower-is-closer → scores ascend. */
    private static boolean isSimilarityMetric(CosmosVectorDistanceFunction fn) {
        return fn == CosmosVectorDistanceFunction.COSINE || fn == CosmosVectorDistanceFunction.DOT_PRODUCT;
    }

    private Mono<OpResult> executeBulk(WorkloadStep step) {
        // op + batchSize + partitionKey validated in validateStepParams.
        Map<String, Object> p = step.params();
        String op = (String) p.get("op");
        int batchSize = ((Number) p.get("batchSize")).intValue();
        Object pkRaw = p.get("partitionKey");
        String idPrefix = p.get("idPrefix") instanceof String s ? s : "wl-bulk-";
        // pkField is optional — when present, every synthesised doc has [pkField]: pkRaw
        // so it materialises in the doc body (Cosmos requires PK fields to exist on writes).
        // For hierarchical containers, pkField is required so HPK extraction works.
        String pkField = p.get("pkField") instanceof String s ? s : null;
        Object template = p.get("template");
        List<BulkOperation> ops = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            String id = idPrefix + Long.toHexString(ThreadLocalRandom.current().nextLong());
            Map<String, Object> doc = pkField == null
                ? Map.of("id", id)
                : synthesizeUpsertDoc(idPrefix, pkField, pkRaw, template);
            // Force the id we just generated onto the doc (synthesizeUpsertDoc picks its own).
            doc = new LinkedHashMap<>(doc);
            doc.put("id", id);
            ops.add(new BulkOperation(op, pkRaw, doc, id));
        }
        BulkRequest req = new BulkRequest(step.container(), ops, null);
        return bulkRunner.run(req)
            // Status code is synthetic — bulk's per-item statuses are folded into requestCharge
            // already; we surface 200 if the request completed (BulkRunner failure paths return
            // Mono.error which executes the error counter in oneOp).
            .map(resp -> new OpResult(resp.totalRequestCharge(), 200));
    }

    /**
     * The marketplace's core transaction as a workload op: build a cart, place an order with the
     * same line items + a correctly-computed total, clear the cart. In verification mode it reads
     * both back and runs the L2 domain invariants — so an SDK round-trip that breaks
     * {@code total == Σ items} or fails to clear the cart is caught even on a 200.
     *
     * <p>Touches two containers: {@code Orders} (2-level HPK {@code (customerId, yearMonth)} on the
     * emulator — the {@code /id} leaf is deferred, see {@link OrderRepo}) and {@code Cart}
     * (PK {@code /customerId}). Synthesised as Maps so we don't round-trip the strict domain
     * validators on the hot path (same pattern as {@code cartUpsert}).
     */
    private Mono<OpResult> executeCheckout(WorkloadStep step, WorkloadRunState state) {
        @SuppressWarnings("unchecked")
        List<?> customerIds = (List<?>) step.params().get("customerIds");
        // Each checkout owns a UNIQUE customer so its cart (keyed by /customerId) isn't raced by a
        // concurrent checkout — otherwise another op writing a fresh cart between this op's clear and
        // read-back would make cart-cleared look violated (a workload artifact, not an SDK bug). The
        // configured ids are a namespace/partition-spread pool we suffix with a unique token.
        String customerPrefix = customerIds.get(ThreadLocalRandom.current().nextInt(customerIds.size())).toString();
        String customerId = customerPrefix + "-" + Long.toHexString(ThreadLocalRandom.current().nextLong());
        String yearMonth = java.time.YearMonth.now().toString();   // YYYY-MM
        String orderId = "wl-ord-" + Long.toHexString(ThreadLocalRandom.current().nextLong());

        int itemCount = 1 + ThreadLocalRandom.current().nextInt(3);
        List<Map<String, Object>> items = new ArrayList<>(itemCount);
        double total = 0.0;
        for (int i = 0; i < itemCount; i++) {
            int qty = 1 + ThreadLocalRandom.current().nextInt(3);
            double unitPrice = 100.0 + ThreadLocalRandom.current().nextInt(500);
            total += qty * unitPrice;
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("productId", "prod-" + String.format("%03d", 1 + ThreadLocalRandom.current().nextInt(30)));
            it.put("sellerId", "seller-" + String.format("%03d", 1 + ThreadLocalRandom.current().nextInt(5)));
            it.put("qty", qty);
            it.put("unitPriceUsd", unitPrice);
            items.add(it);
        }

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", orderId);
        order.put("customerId", customerId);
        order.put("yearMonth", yearMonth);
        order.put("status", "pending");
        order.put("items", items);
        order.put("totalUsd", total);
        order.put("createdAt", Instant.now().toString());

        CosmosAsyncContainer orders = database.getContainer("Orders");
        CosmosAsyncContainer carts = database.getContainer("Cart");
        PartitionKey ordersPk = new com.azure.cosmos.models.PartitionKeyBuilder()
            .add(customerId).add(yearMonth).build();
        PartitionKey cartPk = new PartitionKey(customerId);
        Map<String, Object> fullCart = cartDoc(customerId, items);
        Map<String, Object> emptyCart = cartDoc(customerId, List.of());
        WorkloadVerificationState v = state.vstate;

        // cart has items -> place order -> clear cart.
        return carts.upsertItem(fullCart, cartPk, new CosmosItemRequestOptions())
            .flatMap(r1 -> orders.upsertItem(order, ordersPk, new CosmosItemRequestOptions())
                .flatMap(r2 -> carts.upsertItem(emptyCart, cartPk, new CosmosItemRequestOptions())
                    .flatMap(r3 -> {
                        double writeRu = r1.getRequestCharge() + r2.getRequestCharge() + r3.getRequestCharge();
                        if (v == null) {
                            return Mono.just(new OpResult(writeRu, 200));
                        }
                        return orders.readItem(orderId, ordersPk, Map.class)
                            .zipWith(carts.readItem(customerId, cartPk, Map.class))
                            .map(t -> {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> readOrder = (Map<String, Object>) t.getT1().getItem();
                                @SuppressWarnings("unchecked")
                                Map<String, Object> readCart = (Map<String, Object>) t.getT2().getItem();
                                domainChecker.checkOrder(v, "checkout", "Orders", readOrder);
                                domainChecker.checkCartCleared(v, "checkout", "Cart", readCart);
                                return new OpResult(writeRu + t.getT1().getRequestCharge()
                                    + t.getT2().getRequestCharge(), 200);
                            });
                    })));
    }

    private static Map<String, Object> cartDoc(String customerId, List<Map<String, Object>> items) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", customerId);
        doc.put("customerId", customerId);
        doc.put("items", items);
        doc.put("updatedAt", Instant.now().toString());
        return doc;
    }

    // ----- snapshot -----

    private void snapshot(WorkloadRunState state) {
        try {
            if (!"RUNNING".equals(state.status)) return;
            long now = System.nanoTime();
            long elapsedSec = Duration.ofNanos(now - state.startNanos).toSeconds();
            long deltaOps = 0;
            long deltaErrs = 0;
            double deltaRu = 0;
            long deltaLatNanos = 0;
            for (StepStats s : state.statsByStep.values()) {
                long c = s.count.get();
                long e = s.errors.get();
                double ru = s.totalRuMillis.get() / 1000.0;
                long lat = s.totalLatencyNanos.get();
                deltaOps += (c - s.prevCount);
                deltaErrs += (e - s.prevErrors);
                deltaRu += (ru - s.prevRu);
                deltaLatNanos += (lat - s.prevLatencyNanos);
                s.prevCount = c;
                s.prevErrors = e;
                s.prevRu = ru;
                s.prevLatencyNanos = lat;
            }
            double meanMs = deltaOps == 0 ? 0.0 : (deltaLatNanos / 1_000_000.0) / deltaOps;
            state.timeSeries.add(new WorkloadTimePoint(
                Instant.now(), elapsedSec, deltaOps, deltaErrs, deltaRu, meanMs));
        } catch (Exception e) {
            LOG.warn("workload snapshot tick failed for run {}", state.runId, e);
        }
    }

    private void finalize(WorkloadRunState state, String terminalStatus, String errorMessage) {
        if (state.stopRequested.get() && "COMPLETED".equals(terminalStatus)) {
            state.status = "STOPPED";
        } else {
            state.status = terminalStatus;
        }
        state.endedAt = Instant.now();
        state.errorMessage = errorMessage;
        ScheduledFuture<?> snap = state.snapshotTask;
        if (snap != null) snap.cancel(false);
    }

    @PreDestroy
    void shutdown() {
        for (WorkloadRunState s : registry.all()) {
            s.stopRequested.set(true);
        }
        snapshotter.shutdown();
        try {
            snapshotter.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ----- internal types -----

    private static String stepKey(WorkloadStep s) {
        return s.op() + ":" + s.container();
    }

    private record OpResult(double requestCharge, int statusCode) {}

    /**
     * Mutable in-memory state for one in-flight or completed run. Lives in
     * {@link WorkloadRegistry}. All counters are atomic so the snapshotter can read them
     * concurrently with user loops writing them.
     */
    static final class WorkloadRunState {
        final String runId;
        final WorkloadSpec spec;
        final Instant startedAt;
        final long startNanos;
        final int totalWeight;
        /** Per-run oracle state; {@code null} when the run has verification disabled. */
        final WorkloadVerificationState vstate;
        final Map<String, StepStats> statsByStep = new LinkedHashMap<>();
        final List<WorkloadTimePoint> timeSeries = Collections.synchronizedList(new ArrayList<>());
        final AtomicBoolean stopRequested = new AtomicBoolean(false);

        volatile String status = "PENDING";
        volatile Instant endedAt;
        volatile String errorMessage;
        volatile ScheduledFuture<?> snapshotTask;

        WorkloadRunState(String runId, WorkloadSpec spec, Instant startedAt, WorkloadVerificationState vstate) {
            this.runId = runId;
            this.spec = spec;
            this.startedAt = startedAt;
            this.startNanos = System.nanoTime();
            this.vstate = vstate;
            int sum = 0;
            for (WorkloadStep s : spec.steps()) {
                sum += s.weight();
                statsByStep.put(stepKey(s), new StepStats(s.op(), s.container()));
            }
            this.totalWeight = sum;
        }

        WorkloadProgress toProgress() {
            Instant end = endedAt;
            long elapsedSec = end == null
                ? Math.max(0, Duration.between(startedAt, Instant.now()).toSeconds())
                : Math.max(0, Duration.between(startedAt, end).toSeconds());
            List<WorkloadOpStats> byStep = new ArrayList<>(statsByStep.size());
            long totalCount = 0, totalErrs = 0;
            double totalRu = 0;
            Histogram aggHist = new Histogram(HISTOGRAM_MAX_NANOS, HISTOGRAM_SIGNIFICANT_DIGITS);
            for (StepStats s : statsByStep.values()) {
                WorkloadOpStats os = s.snapshot(elapsedSec);
                byStep.add(os);
                totalCount += os.count();
                totalErrs += os.errorCount();
                totalRu += os.totalRu();
                Histogram h = s.histogramCopy();
                aggHist.add(h);
            }
            double opsPerSec = elapsedSec == 0 ? 0.0 : (double) totalCount / elapsedSec;
            double ruPerSec = elapsedSec == 0 ? 0.0 : totalRu / elapsedSec;
            double meanMs = aggHist.getTotalCount() == 0 ? 0.0
                : aggHist.getMean() / 1_000_000.0;
            double p50 = aggHist.getValueAtPercentile(50) / 1_000_000.0;
            double p95 = aggHist.getValueAtPercentile(95) / 1_000_000.0;
            double p99 = aggHist.getValueAtPercentile(99) / 1_000_000.0;
            double maxMs = aggHist.getMaxValue() / 1_000_000.0;
            WorkloadOpStats overall = new WorkloadOpStats(
                "*", "*", totalCount, totalErrs, totalRu, opsPerSec, ruPerSec,
                meanMs, p50, p95, p99, maxMs);
            String verificationLevel = vstate == null ? null : vstate.level();
            AnomalySummary anomalySummary = vstate == null ? null : vstate.summary(MAX_SUMMARY_SAMPLES);
            return new WorkloadProgress(
                runId, spec.name(), status, startedAt, end, elapsedSec,
                spec.durationSeconds(), spec.concurrency(),
                overall, byStep, new ArrayList<>(timeSeries), errorMessage,
                verificationLevel, anomalySummary);
        }
    }

    /** Per-step counters + HdrHistogram. Counters are atomic; histogram is synchronized
     *  on itself for the small write window. */
    static final class StepStats {
        final String op;
        final String container;
        final AtomicLong count = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        // RU stored as millis-RU (RU * 1000) to keep an atomic long; sub-RU precision OK.
        final AtomicLong totalRuMillis = new AtomicLong();
        final AtomicLong totalLatencyNanos = new AtomicLong();
        final Histogram latency = new Histogram(HISTOGRAM_MAX_NANOS, HISTOGRAM_SIGNIFICANT_DIGITS);

        // Snapshot trackers (used by snapshotter only — single-threaded reader).
        long prevCount, prevErrors;
        double prevRu;
        long prevLatencyNanos;

        StepStats(String op, String container) {
            this.op = op;
            this.container = container;
        }

        void recordSuccess(long latNanos, double ru) {
            count.incrementAndGet();
            totalRuMillis.addAndGet(Math.round(ru * 1000.0));
            totalLatencyNanos.addAndGet(latNanos);
            synchronized (latency) {
                latency.recordValue(Math.min(latNanos, HISTOGRAM_MAX_NANOS - 1));
            }
        }

        void recordError(long latNanos) {
            count.incrementAndGet();
            errors.incrementAndGet();
            totalLatencyNanos.addAndGet(latNanos);
            synchronized (latency) {
                latency.recordValue(Math.min(latNanos, HISTOGRAM_MAX_NANOS - 1));
            }
        }

        WorkloadOpStats snapshot(long elapsedSec) {
            long c = count.get();
            long e = errors.get();
            double ru = totalRuMillis.get() / 1000.0;
            double opsPerSec = elapsedSec == 0 ? 0.0 : (double) c / elapsedSec;
            double ruPerSec = elapsedSec == 0 ? 0.0 : ru / elapsedSec;
            Histogram copy = histogramCopy();
            double meanMs = copy.getTotalCount() == 0 ? 0.0 : copy.getMean() / 1_000_000.0;
            double p50 = copy.getValueAtPercentile(50) / 1_000_000.0;
            double p95 = copy.getValueAtPercentile(95) / 1_000_000.0;
            double p99 = copy.getValueAtPercentile(99) / 1_000_000.0;
            double maxMs = copy.getMaxValue() / 1_000_000.0;
            return new WorkloadOpStats(op, container, c, e, ru, opsPerSec, ruPerSec,
                meanMs, p50, p95, p99, maxMs);
        }

        Histogram histogramCopy() {
            synchronized (latency) {
                return latency.copy();
            }
        }
    }
}
