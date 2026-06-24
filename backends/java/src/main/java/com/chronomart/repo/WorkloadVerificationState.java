package com.chronomart.repo;

import com.chronomart.web.dto.AnomalySummary;
import com.chronomart.web.dto.WorkloadAnomaly;
import com.chronomart.web.dto.WorkloadVerification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
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

    /** Soft cap on per-(user,key) session-floor entries so a huge keyspace × concurrency can't
     *  grow the session map without bound; beyond it, monotonic/read-your-write tracking degrades. */
    private static final int MAX_SESSION_FLOORS = 2_000_000;

    /** Session-floor source tags — which kind of op established the current floor for a (user,key). */
    static final int SOURCE_WRITE = 0;
    static final int SOURCE_READ = 1;

    private final String runId;
    private final WorkloadVerification config;
    private final String level;
    private final boolean sessionTracked;
    private final String prefix;
    private final int size;
    private final double sampleRate;

    /** The reference model: one {@link KeyState} per {@code container|key}. */
    private final ConcurrentHashMap<String, KeyState> keyStates = new ConcurrentHashMap<>();
    /** Per-(user,container,key) session floor: {@code [maxSeqSeen, source]}. Single-writer per
     *  entry (a virtual user runs its ops sequentially), so the array is updated without locking. */
    private final ConcurrentHashMap<String, long[]> sessionFloors = new ConcurrentHashMap<>();
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
        this.level = config.levelOrDefault();
        this.sessionTracked = "session".equals(this.level);
        WorkloadVerification.Keyspace ks = config.keyspaceOrDefault();
        this.prefix = ks.prefixOrDefault();
        this.size = ks.sizeOrDefault();
        this.sampleRate = config.sampleRateOrDefault();
    }

    String runId() {
        return runId;
    }

    String level() {
        return level;
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

    // ----- reference model: write lifecycle + queries -----

    private KeyState keyState(String container, String key) {
        return keyStates.computeIfAbsent(container + "|" + key, k -> new KeyState());
    }

    /**
     * Begin a write to {@code (container,key)}: allocate the next strictly-increasing sequence and
     * mark it in-flight. The caller MUST later call {@link #ackWrite} (on success) or
     * {@link #failWrite} (on error) so the model knows whether the value became durable.
     *
     * <p>We also track whether the write is the <b>sole writer</b> for its whole life (no other
     * write to the key overlapped it). Only such non-concurrent writes have an unambiguous place in
     * the version order — concurrent writes can commit in either physical order regardless of which
     * got the higher seq — so only they may anchor a temporal (stale/read-your-writes) judgment.
     */
    long beginWrite(String container, String key) {
        KeyState ks = keyState(container, key);
        synchronized (ks) {
            long seq = ks.allocSeq.incrementAndGet();
            if (ks.inFlight.isEmpty()) {
                ks.soleCandidates.add(seq);          // no overlap so far
            } else {
                ks.soleCandidates.clear();           // everyone now in-flight is concurrent
            }
            ks.inFlight.add(seq);
            return seq;
        }
    }

    /**
     * Mark a write durable. Returns {@code true} when the write was <b>settled</b> — it was the sole
     * writer for its entire interval, so its seq is the unambiguous latest committed value and may
     * anchor temporal checks. Concurrent writes return {@code false}.
     */
    boolean ackWrite(String container, String key, long seq) {
        KeyState ks = keyState(container, key);
        synchronized (ks) {
            ks.inFlight.remove(seq);
            ks.latestAckedSeq.accumulateAndGet(seq, Math::max);
            ks.tombstoned = false;
            if (ks.soleCandidates.remove(seq)) {
                ks.settledSeq.accumulateAndGet(seq, Math::max);
                return true;
            }
            return false;
        }
    }

    /** Mark a write failed: it leaves in-flight but may or may not have applied at the server, so a
     *  later read observing its seq is still legitimate (not a phantom). */
    void failWrite(String container, String key, long seq) {
        KeyState ks = keyState(container, key);
        synchronized (ks) {
            ks.inFlight.remove(seq);
            ks.soleCandidates.remove(seq);
        }
    }

    /** Highest sequence whose write has been acknowledged for {@code (container,key)} (0 = none). */
    long latestAckedSeq(String container, String key) {
        return keyState(container, key).latestAckedSeq.get();
    }

    /** Highest <b>settled</b> (non-concurrent, unambiguously-ordered) acked seq — the floor a
     *  linearizable read must not fall below. Concurrent writes never advance this. */
    long settledSeq(String container, String key) {
        return keyState(container, key).settledSeq.get();
    }

    /** Highest sequence ever allocated for {@code (container,key)} — a read above this is a phantom. */
    long maxAllocatedSeq(String container, String key) {
        return keyState(container, key).allocSeq.get();
    }

    boolean tombstoned(String container, String key) {
        return keyState(container, key).tombstoned;
    }

    boolean sessionTracked() {
        return sessionTracked;
    }

    // ----- per-user session floors (read-your-writes + monotonic reads) -----

    private static String floorKey(int userIdx, String container, String key) {
        return userIdx + "|" + container + "|" + key;
    }

    /** The seq floor this user has established for the key (max of its acked writes + prior reads). */
    long sessionFloorSeq(int userIdx, String container, String key) {
        long[] f = sessionFloors.get(floorKey(userIdx, container, key));
        return f == null ? 0 : f[0];
    }

    /** Which op set the current floor — {@link #SOURCE_WRITE} or {@link #SOURCE_READ} (-1 if none). */
    int sessionFloorSource(int userIdx, String container, String key) {
        long[] f = sessionFloors.get(floorKey(userIdx, container, key));
        return f == null ? -1 : (int) f[1];
    }

    /**
     * Raise this user's floor for the key if {@code seq} is newer. No-op unless the run asserts
     * session consistency. Single-writer per entry (sequential per virtual user), so the in-place
     * array update needs no lock.
     */
    void bumpSessionFloor(int userIdx, String container, String key, long seq, int source) {
        if (!sessionTracked) return;
        String fk = floorKey(userIdx, container, key);
        long[] existing = sessionFloors.get(fk);
        if (existing == null) {
            if (sessionFloors.size() >= MAX_SESSION_FLOORS) return; // soft memory guard
            sessionFloors.putIfAbsent(fk, new long[] {seq, source});
            return;
        }
        if (seq > existing[0]) {
            existing[0] = seq;
            existing[1] = source;
        }
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
        // Use getAndIncrement() so the check-and-reserve is a single atomic step; a plain
        // get()+incrementAndGet() pair has a TOCTOU window where concurrent threads both pass
        // the guard and overfill the buffer.
        if (retainedCount.getAndIncrement() < MAX_RETAINED_ANOMALIES) {
            retained.add(a);
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

    /**
     * Reference-model state for one {@code (container,key)} register. {@code allocSeq} is the
     * monotonic sequence source (every write attempt bumps it); {@code latestAckedSeq} is the
     * highest durable seq; {@code inFlight} holds seqs whose write started but hasn't acked yet.
     * All fields are safe for concurrent virtual users (atomics + a concurrent set).
     */
    static final class KeyState {
        final AtomicLong allocSeq = new AtomicLong(0);
        final AtomicLong latestAckedSeq = new AtomicLong(0);
        /** Highest acked seq that was the sole writer for its whole interval (unambiguous order). */
        final AtomicLong settledSeq = new AtomicLong(0);
        final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
        /** In-flight seqs that have had no overlapping write so far; cleared the moment another
         *  write joins (they all become concurrent). Guarded by {@code synchronized(this)}. */
        final Set<Long> soleCandidates = ConcurrentHashMap.newKeySet();
        volatile boolean tombstoned = false;
    }
}
