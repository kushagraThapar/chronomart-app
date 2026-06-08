package com.chronomart.repo;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single source of truth for whether the {@code ProductVectors} container is provisioned
 * and ready to serve vector search. Written by {@link ContainerInitializer} during boot
 * (only on a successful read or create), read by the capability manifest and the vector
 * controller.
 *
 * <p>The harness explicitly does not lie about features it cannot deliver: when
 * provisioning fails (e.g. the vNext emulator rejects the DiskANN container body, same
 * wire-format risk as the 3-level HPK incident), this stays {@code false} so:
 * <ul>
 *   <li>{@code GET /api/v1/_meta/capabilities} reports {@code vectorSearch=false} —
 *       upstream UIs/tests can branch correctly.</li>
 *   <li>{@code POST /api/v1/vector/search} returns 501 Not Implemented (already
 *       declared in the OpenAPI contract for this path) with a body pointing at the
 *       diagnostics endpoint rather than a generic Cosmos 404.</li>
 * </ul>
 */
@Component
public class VectorContainerStatus {

    private final AtomicBoolean ready = new AtomicBoolean(false);

    public void markReady() {
        ready.set(true);
    }

    public boolean isReady() {
        return ready.get();
    }
}
