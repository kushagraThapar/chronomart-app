package com.chronomart.oracle;

import com.chronomart.web.dto.OpHistoryRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline register-history analyzer — a second, independent implementation of the temporal oracle
 * that consumes a verification run's downloaded op history (single-register pointRead/pointUpsert
 * ops) and checks <b>real-time linearizability</b> per key. Where the live oracle judges each op as
 * it happens (against a snapshot floor), this has the whole timeline, so it recomputes which writes
 * were <em>settled</em> (sole writer for their interval, hence unambiguously ordered) directly from
 * the op intervals and uses real-time happens-before to flag:
 *
 * <ul>
 *   <li><b>STALE_READ</b> — a read observed seq V while a settled write with seq &gt; V had already
 *       completed (ended) before the read began. After a settled write commits, every later write
 *       has a higher seq and commits later, so the register can't go backwards — reading V is
 *       genuinely stale (a linearizability violation).</li>
 *   <li><b>PHANTOM_READ</b> — a read observed a seq that was never written for the key.</li>
 *   <li><b>LOST_WRITE</b> — a read returned not-found although a settled write had completed before
 *       it began (the keyspace workload never deletes).</li>
 * </ul>
 *
 * <p>Because it checks linearizability (the strongest model), a run executed against a
 * session-consistency account may legitimately show real-time staleness here — the verdict is a
 * linearizability lens, most directly meaningful for {@code level=strong} runs. It exists to (a)
 * independently corroborate the live oracle, (b) turn the downloadable artifact into a verdict, and
 * (c) run identically over any SDK backend's history.
 *
 * <p>CLI: {@code java -cp app.jar com.chronomart.oracle.OfflineHistoryAnalyzer history.json}; exits
 * non-zero when any violation is found, so it can gate CI.
 */
public final class OfflineHistoryAnalyzer {

    private OfflineHistoryAnalyzer() {}

    /** A linearizability violation re-derived from the history. */
    public record Violation(String code, String key, String detail, Long observedSeq, Long expectedSeq) {}

    /** Result of analyzing one history. */
    public record Verdict(int keysAnalyzed, long readsChecked, long writesChecked, List<Violation> violations) {
        public boolean clean() {
            return violations.isEmpty();
        }
    }

    public static Verdict analyze(List<OpHistoryRecord> history) {
        Map<String, List<OpHistoryRecord>> byKey = new LinkedHashMap<>();
        for (OpHistoryRecord r : history) {
            if (r.key() != null) {
                byKey.computeIfAbsent(r.container() + "|" + r.key(), k -> new ArrayList<>()).add(r);
            }
        }
        List<Violation> violations = new ArrayList<>();
        long reads = 0;
        long writes = 0;
        for (List<OpHistoryRecord> recs : byKey.values()) {
            List<OpHistoryRecord> okWrites = new ArrayList<>();
            for (OpHistoryRecord r : recs) {
                if ("pointUpsert".equals(r.op()) && OpHistoryRecord.OUTCOME_OK.equals(r.outcome()) && r.writeSeq() != null) {
                    okWrites.add(r);
                }
            }
            writes += okWrites.size();
            long maxSeq = 0;
            for (OpHistoryRecord w : okWrites) {
                maxSeq = Math.max(maxSeq, w.writeSeq());
            }
            // Settled = no other write overlapped this write's [begin,end] interval.
            List<OpHistoryRecord> settled = new ArrayList<>();
            for (OpHistoryRecord w : okWrites) {
                boolean overlapped = false;
                for (OpHistoryRecord o : okWrites) {
                    if (o != w && w.beginNanos() < o.endNanos() && o.beginNanos() < w.endNanos()) {
                        overlapped = true;
                        break;
                    }
                }
                if (!overlapped) {
                    settled.add(w);
                }
            }
            for (OpHistoryRecord r : recs) {
                if (!"pointRead".equals(r.op())) {
                    continue;
                }
                reads++;
                String key = r.key();
                if (OpHistoryRecord.OUTCOME_NOTFOUND.equals(r.outcome())) {
                    long lostFloor = settledFloorBefore(settled, r.beginNanos(), 0);
                    if (lostFloor > 0) {
                        violations.add(new Violation("LOST_WRITE", key,
                            "read not-found but settled seq " + lostFloor + " committed before it began",
                            null, lostFloor));
                    }
                    continue;
                }
                Long v = r.observedSeq();
                if (v == null) {
                    continue;
                }
                if (v > maxSeq) {
                    violations.add(new Violation("PHANTOM_READ", key,
                        "observed seq " + v + " exceeds max written seq " + maxSeq, v, null));
                    continue;
                }
                long staleFloor = settledFloorBefore(settled, r.beginNanos(), v);
                if (staleFloor > 0) {
                    violations.add(new Violation("STALE_READ", key,
                        "observed seq " + v + " but settled seq " + staleFloor + " committed before the read began",
                        v, staleFloor));
                }
            }
        }
        return new Verdict(byKey.size(), reads, writes, violations);
    }

    /** Highest settled write seq that is {@code > minSeq} and ended before {@code beginNanos}. */
    private static long settledFloorBefore(List<OpHistoryRecord> settled, long beginNanos, long minSeq) {
        long floor = 0;
        for (OpHistoryRecord w : settled) {
            if (w.writeSeq() > minSeq && w.endNanos() < beginNanos) {
                floor = Math.max(floor, w.writeSeq());
            }
        }
        return floor;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: OfflineHistoryAnalyzer <history.json>");
            System.exit(2);
            return;
        }
        ObjectMapper mapper = new ObjectMapper();
        List<OpHistoryRecord> history = mapper.readValue(new File(args[0]), new TypeReference<>() {});
        Verdict verdict = analyze(history);
        System.out.printf("keys=%d reads=%d writes=%d violations=%d%n",
            verdict.keysAnalyzed(), verdict.readsChecked(), verdict.writesChecked(), verdict.violations().size());
        int shown = 0;
        for (Violation x : verdict.violations()) {
            System.out.printf("  %-12s %-16s %s%n", x.code(), x.key(), x.detail());
            if (++shown >= 50) {
                System.out.printf("  ... and %d more%n", verdict.violations().size() - shown);
                break;
            }
        }
        System.out.println(verdict.clean() ? "VERDICT: LINEARIZABLE (no violations)" : "VERDICT: VIOLATIONS FOUND");
        System.exit(verdict.clean() ? 0 : 1);
    }
}
