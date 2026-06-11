package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import com.chronomart.web.dto.QueryRequest;
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
 * </ul>
 *
 * <p>Other ops (hpkPointRead, vectorSearch, bulk, ...) reject with
 * {@code "op '<name>' is not yet implemented"} at spec-validation time so PR2 can light
 * them up without breaking the wire contract.
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
     *  around 32-64 concurrent ops before RU throttling dominates. */
    public static final int MAX_CONCURRENCY = 64;

    /** Ops the engine knows how to execute today. Anything else is rejected at start. */
    public static final List<String> SUPPORTED_OPS = List.of("pointRead", "query", "cartUpsert");

    /** Ops named in the OpenAPI capability manifest but not yet wired. Listed here so
     *  validation errors are friendly ("not yet implemented") not generic ("unknown op"). */
    public static final List<String> KNOWN_OPS = List.of(
        "pointRead", "pointUpsert", "query", "hpkPointRead", "vectorSearch", "bulk", "cartUpsert"
    );

    /** Time-series snapshot interval. 1s matches the chart resolution in the UI. */
    private static final Duration SNAPSHOT_INTERVAL = Duration.ofSeconds(1);

    /** HdrHistogram tracks nanoseconds, 3 significant figures. Caps at ~1 hour per op so
     *  catastrophic timeouts are still recordable. ~50KB per histogram, well-bounded. */
    private static final long HISTOGRAM_MAX_NANOS = Duration.ofHours(1).toNanos();
    private static final int HISTOGRAM_SIGNIFICANT_DIGITS = 3;

    private final CosmosAsyncDatabase database;
    private final ContainerAllowList allowList;
    private final QueryRunner queryRunner;
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
                          WorkloadRegistry registry) {
        this.database = database;
        this.allowList = allowList;
        this.queryRunner = queryRunner;
        this.queryRunner.allowedContainers(); // touch to ensure bean wired
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
        WorkloadRunState state = new WorkloadRunState(runId, spec, Instant.now());
        registry.register(state);
        Mono<Void> driver = driveRun(state)
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
            if (!SUPPORTED_OPS.contains(s.op())) {
                throw new IllegalArgumentException(
                    "op '" + s.op() + "' is not yet implemented (PR2). Supported in v1: " + SUPPORTED_OPS);
            }
            validateStepParams(s);
        }
    }

    private void validateStepParams(WorkloadStep s) {
        Map<String, Object> p = s.params() == null ? Map.of() : s.params();
        switch (s.op()) {
            case "pointRead" -> {
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
                if (!(p.get("query") instanceof String q) || q.isBlank()) {
                    throw new IllegalArgumentException("query requires non-blank params.query");
                }
                Boolean xPart = (Boolean) p.get("enableCrossPartition");
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
            default -> throw new IllegalStateException("unreachable: validated op " + s.op());
        }
    }

    // ----- run driver -----

    private Mono<Void> driveRun(WorkloadRunState state) {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(state.spec.durationSeconds()).toNanos();
        state.status = "RUNNING";
        return Flux.range(0, state.spec.concurrency())
            .flatMap(i -> userLoop(state, deadlineNanos), state.spec.concurrency())
            .then();
    }

    private Mono<Void> userLoop(WorkloadRunState state, long deadlineNanos) {
        return Mono.defer(() -> oneOp(state))
            .repeat(() -> System.nanoTime() < deadlineNanos && !state.stopRequested.get())
            .then();
    }

    private Mono<Void> oneOp(WorkloadRunState state) {
        WorkloadStep step = pickStep(state.spec.steps(), state.totalWeight);
        StepStats stats = state.statsByStep.get(stepKey(step));
        long start = System.nanoTime();
        return execute(step)
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

    private Mono<OpResult> execute(WorkloadStep step) {
        return switch (step.op()) {
            case "pointRead" -> executePointRead(step);
            case "query" -> executeQuery(step);
            case "cartUpsert" -> executeCartUpsert(step);
            default -> Mono.error(new IllegalStateException("unsupported op: " + step.op()));
        };
    }

    private Mono<OpResult> executePointRead(WorkloadStep step) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) step.params().get("ids");
        Object pksRaw = step.params().get("partitionKeys");
        int i = ThreadLocalRandom.current().nextInt(ids.size());
        String id = ids.get(i);
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

    private Mono<OpResult> executeQuery(WorkloadStep step) {
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
        Boolean xPart = (Boolean) p.get("enableCrossPartition");
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
        @SuppressWarnings("unchecked")
        List<String> customerIds = (List<String>) step.params().get("customerIds");
        String customerId = customerIds.get(ThreadLocalRandom.current().nextInt(customerIds.size()));
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
        final Map<String, StepStats> statsByStep = new LinkedHashMap<>();
        final List<WorkloadTimePoint> timeSeries = Collections.synchronizedList(new ArrayList<>());
        final AtomicBoolean stopRequested = new AtomicBoolean(false);

        volatile String status = "PENDING";
        volatile Instant endedAt;
        volatile String errorMessage;
        volatile ScheduledFuture<?> snapshotTask;

        WorkloadRunState(String runId, WorkloadSpec spec, Instant startedAt) {
            this.runId = runId;
            this.spec = spec;
            this.startedAt = startedAt;
            this.startNanos = System.nanoTime();
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
            return new WorkloadProgress(
                runId, spec.name(), status, startedAt, end, elapsedSec,
                spec.durationSeconds(), spec.concurrency(),
                overall, byStep, new ArrayList<>(timeSeries), errorMessage);
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
