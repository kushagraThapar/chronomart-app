package com.chronomart.repo;

import com.chronomart.web.dto.AnomalySummary;
import com.chronomart.web.dto.WorkloadAnomaly;
import com.chronomart.web.dto.WorkloadVerification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-run, mutable state for the workload correctness oracle. Created once per verification-enabled
 * run and shared (thread-safely) across all virtual-user loops. Owns:
 *
 * <ul>
 *   <li>the <b>owned keyspace</b> mapping — {@code keyAt(i)} / {@code pkValueFor(key)} — so reads
 *       and writes address the same keys and the same partitions deterministically;</li>
 *   <li>a monotonic per-{@code (container,key)} <b>sequence source</b> ({@link #nextSeq}) so each
 *       write to a key gets a strictly increasing {@code seq} (the spine of the later
 *       reference-model oracle);</li>
 *   <li>the global op counter and the <b>anomaly sink</b> (bounded sample buffer + full counters),
 *       plus the {@link #summary} roll-up surfaced on the run's progress.</li>
 * </ul>
 *
 * <p>This slice records anomalies and seqs; the temporal reference-model checks (read-your-write,
 * stale, lost, phantom) build on these same counters in a later slice.
 */
final class WorkloadVerificationState {

    /** Spread keys across this many synthetic partitions so a single-PK keyspace still exercises
     *  cross-partition routing. */
    static final int KEYSPACE_PARTITIONS = 16;

    /** Cap on retained anomaly samples (the endpoint pages these; counters remain exact). */
    private static final int MAX_RETAINED_ANOMALIES = 10_000;

    private final String runId;
    private final WorkloadVerification config;
    private final String prefix;
    private final int size;
    private final double sampleRate;

    private final ConcurrentHashMap<String, AtomicLong> seqByKey = new ConcurrentHashMap<>();
    private final AtomicLong opSeqGlobal = new AtomicLong(0);

    private final Queue<WorkloadAnomaly> retained = new ConcurrentLinkedQueue<>();
    private final AtomicLong anomalyTotal = new AtomicLong(0);
    private final AtomicLong anomalyErrors = new AtomicLong(0);
    private final AtomicLong anomalyWarns = new AtomicLong(0);
    private final AtomicLong retainedCount = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> byCode = new ConcurrentHashMap<>();

    WorkloadVerificationState(String runId, WorkloadVerification config) {
        this.runId = runId;
        this.config = config;
        WorkloadVerification.Keyspace ks = config.keyspaceOrDefault();
        this.prefix = ks.prefixOrDefault();
        this.size = ks.sizeOrDefault();
        this.sampleRate = config.sampleRateOrDefault();
    }

    String runId() {
        return runId;
    }

    String level() {
        return config.levelOrDefault();
    }

    int keyspaceSize() {
        return size;
    }

    // ----- keyspace addressing -----

    /** Key at index {@code i} in the owned keyspace, e.g. {@code wl-000042}. */
    String keyAt(int i) {
        return prefix + "-" + String.format("%06d", i);
    }

    /** A uniformly random key from the keyspace. */
    String randomKey() {
        return keyAt(ThreadLocalRandom.current().nextInt(size));
    }

    /** Deterministic partition-key value for a key — identical for the writer and any reader. */
    String pkValueFor(String key) {
        return prefix + "-pk-" + Math.floorMod(VerifiedValue.stableHash(key), KEYSPACE_PARTITIONS);
    }

    // ----- sequence + op counters -----

    /** Next strictly-increasing sequence for {@code (container,key)} (first write gets 1). */
    long nextSeq(String container, String key) {
        return seqByKey.computeIfAbsent(container + "|" + key, k -> new AtomicLong(0)).incrementAndGet();
    }

    long nextOpSeq() {
        return opSeqGlobal.incrementAndGet();
    }

    /** Sampling gate — true when this op should be verified live (full rate verifies everything). */
    boolean shouldVerify() {
        return sampleRate >= 1.0 || ThreadLocalRandom.current().nextDouble() < sampleRate;
    }

    // ----- anomaly sink -----

    void record(WorkloadAnomaly a) {
        anomalyTotal.incrementAndGet();
        if (WorkloadAnomaly.SEVERITY_WARN.equals(a.severity())) {
            anomalyWarns.incrementAndGet();
        } else {
            anomalyErrors.incrementAndGet();
        }
        byCode.computeIfAbsent(a.code(), c -> new AtomicLong(0)).incrementAndGet();
        if (retainedCount.get() < MAX_RETAINED_ANOMALIES) {
            retained.add(a);
            retainedCount.incrementAndGet();
        }
    }

    long errorCount() {
        return anomalyErrors.get();
    }

    /** Roll-up for {@code WorkloadProgress}; {@code maxSamples} bounds the inlined sample list. */
    AnomalySummary summary(int maxSamples) {
        if (anomalyTotal.get() == 0) {
            return null;
        }
        Map<String, Long> codes = new LinkedHashMap<>();
        byCode.forEach((k, v) -> codes.put(k, v.get()));
        List<WorkloadAnomaly> samples = new ArrayList<>(Math.min(maxSamples, retained.size()));
        for (WorkloadAnomaly a : retained) {
            if (samples.size() >= maxSamples) {
                break;
            }
            samples.add(a);
        }
        return new AnomalySummary(anomalyTotal.get(), anomalyErrors.get(), anomalyWarns.get(), codes, samples);
    }

    /** A page of the retained anomalies, in detection order. */
    List<WorkloadAnomaly> anomalies(int offset, int limit) {
        List<WorkloadAnomaly> all = new ArrayList<>(retained);
        if (offset >= all.size()) {
            return List.of();
        }
        return all.subList(offset, Math.min(all.size(), offset + limit));
    }

    long anomalyCount() {
        return anomalyTotal.get();
    }
}
