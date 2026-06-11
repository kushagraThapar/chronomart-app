package com.chronomart.repo;

import com.chronomart.repo.WorkloadEngine.WorkloadRunState;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bounded ring buffer of workload runs (50 most recent, oldest evicted). Keeps
 * {@link WorkloadEngine} thin — the engine deals with one run at a time, the registry
 * handles cross-run lifecycle.
 *
 * <p>Two views are maintained:
 * <ul>
 *   <li>{@code byId} — O(1) lookup for {@code GET /workloads/{runId}}.</li>
 *   <li>{@code order} — FIFO of run IDs for eviction (most-recent-first when read).</li>
 * </ul>
 *
 * <p>This is in-memory only; restarting the backend clears history. That matches the
 * harness's posture (the {@link DiagnosticsRecorder} works the same way) and avoids
 * dragging in a real datastore for what is fundamentally ephemeral test data.
 */
@Component
public class WorkloadRegistry {

    /** Mirrors the choice the user made via the elicitation form on PR1 design. */
    public static final int CAP = 50;

    private final ConcurrentMap<String, WorkloadRunState> byId = new ConcurrentHashMap<>();
    private final Deque<String> order = new ArrayDeque<>(CAP + 1);
    private final Object orderLock = new Object();

    public void register(WorkloadRunState state) {
        byId.put(state.runId, state);
        synchronized (orderLock) {
            order.addFirst(state.runId);
            while (order.size() > CAP) {
                String evicted = order.removeLast();
                byId.remove(evicted);
            }
        }
    }

    public WorkloadRunState get(String runId) {
        return byId.get(runId);
    }

    /** Returns runs in most-recent-first order. */
    public Collection<WorkloadRunState> all() {
        synchronized (orderLock) {
            List<WorkloadRunState> out = new ArrayList<>(order.size());
            for (String id : order) {
                WorkloadRunState s = byId.get(id);
                if (s != null) out.add(s);
            }
            return out;
        }
    }
}
