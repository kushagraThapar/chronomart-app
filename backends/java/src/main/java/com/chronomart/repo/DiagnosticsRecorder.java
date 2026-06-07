package com.chronomart.repo;

import com.azure.core.util.Context;
import com.azure.cosmos.CosmosDiagnosticsContext;
import com.azure.cosmos.CosmosDiagnosticsHandler;
import com.chronomart.web.dto.DiagnosticsEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded in-memory ring buffer + {@link CosmosDiagnosticsHandler} implementation.
 *
 * <p>Wired into the Cosmos client via {@code CosmosClientTelemetryConfig.diagnosticsHandler()}
 * with zero thresholds — every operation surfaces here, not just slow ones. Snapshot the
 * {@link CosmosDiagnosticsContext} into an immutable {@link DiagnosticsEntry} immediately;
 * the SDK is free to reuse the context after the handler returns.
 *
 * <p>Backed by a {@code synchronized ArrayDeque} with {@link #CAP} = 1000 entries. On
 * overflow the oldest entry is evicted. This is dev/diagnostic-only storage — not
 * intended to survive a process restart and not intended to back production telemetry
 * (use OTel for that, see {@code p1-java-otel}).
 *
 * <p>Self-noise note: this handler also fires for SDK-internal operations (metadata reads,
 * the container reads we make from {@code /_meta/caches} itself, etc.). We filter out
 * {@code resourceType=Account} bootstrap chatter to keep the dev signal usable but
 * otherwise leave everything in — including {@code /_meta/caches} self-traffic — so
 * callers can see the diagnostic endpoint's own footprint.
 */
@Component
public class DiagnosticsRecorder implements CosmosDiagnosticsHandler {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsRecorder.class);

    /** Max entries retained. Older entries are evicted on overflow. */
    public static final int CAP = 1000;

    private final Deque<DiagnosticsEntry> buf = new ArrayDeque<>(CAP);
    private final Object lock = new Object();

    @Override
    public void handleDiagnostics(CosmosDiagnosticsContext ctx, Context azCtx) {
        try {
            // Account-level bootstrap (database account discovery) fires hundreds of times
            // at startup; useless noise for end-user request diagnostics.
            String resourceType = ctx.getResourceType();
            if ("DatabaseAccount".equalsIgnoreCase(resourceType)) {
                return;
            }

            Duration dur = ctx.getDuration();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("database", ctx.getDatabaseName());
            details.put("container", ctx.getContainerName());
            details.put("resourceType", resourceType);
            details.put("isPointOperation", ctx.isPointOperation());
            details.put("retryCount", ctx.getRetryCount());
            details.put("subStatusCode", ctx.getSubStatusCode());
            details.put("isFailure", ctx.isFailure());
            details.put("regions", snapshotStrings(ctx.getContactedRegionNames()));
            if (ctx.getMaxItemCount() != null) details.put("maxItemCount", ctx.getMaxItemCount());
            if (ctx.getActualItemCount() != null) details.put("actualItemCount", ctx.getActualItemCount());
            // Drop nulls so the per-entry payload stays small (Jackson NON_NULL handles top-level
            // but not nested map values).
            details.values().removeIf(java.util.Objects::isNull);

            DiagnosticsEntry entry = new DiagnosticsEntry(
                Instant.now(),
                join(ctx.getOperationType(), resourceType),
                dur == null ? null : dur.toNanos() / 1_000_000.0,
                (double) ctx.getTotalRequestCharge(),
                ctx.getStatusCode(),
                ctx.getTrackingId(),
                details
            );

            synchronized (lock) {
                if (buf.size() >= CAP) buf.removeFirst();
                buf.addLast(entry);
            }
        } catch (RuntimeException e) {
            // Never let a diagnostic capture failure bubble back into the SDK call path.
            log.warn("DiagnosticsRecorder.handleDiagnostics threw — entry dropped", e);
        }
    }

    /** Returns at most {@code n} most-recent entries (oldest first within the slice). */
    public List<DiagnosticsEntry> last(int n) {
        synchronized (lock) {
            int size = buf.size();
            int from = Math.max(0, size - n);
            // Defensive copy so callers can iterate without holding the lock.
            return new ArrayList<>(buf).subList(from, size).stream().toList();
        }
    }

    /** Test/debug helper: current buffer size. Not exposed via HTTP. */
    public int size() {
        synchronized (lock) {
            return buf.size();
        }
    }

    private static String join(String op, String rt) {
        if (op == null && rt == null) return "unknown";
        if (op == null) return rt;
        if (rt == null) return op;
        return op + " " + rt;
    }

    private static List<String> snapshotStrings(Collection<String> c) {
        if (c == null || c.isEmpty()) return List.of();
        return new ArrayList<>(c);
    }
}
