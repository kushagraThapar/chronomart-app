package com.chronomart.web;

import com.azure.cosmos.CosmosAsyncDatabase;
import com.chronomart.config.ChronomartProperties;
import com.chronomart.repo.ContainerAllowList;
import com.chronomart.repo.DiagnosticsRecorder;
import com.chronomart.web.dto.CacheSnapshot;
import com.chronomart.web.dto.CacheSnapshot.ContainerCacheEntry;
import com.chronomart.web.dto.CacheSnapshot.PkRangeCacheEntry;
import com.chronomart.web.dto.CacheSnapshot.RangeEntry;
import com.chronomart.web.dto.CapabilityManifest;
import com.chronomart.web.dto.DiagnosticsEntry;
import com.chronomart.web.dto.FeedRangeDto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cross-cutting metadata endpoints under {@code /api/v1/_meta/*}.
 *
 * <p>Houses both the static capability manifest and the diagnostic surfaces wired in PR7
 * ({@code /diagnostics}, {@code /caches}, {@code /feed-ranges}). Diagnostic endpoints
 * return {@link Mono} where SDK round-trips are involved; capabilities stays sync.
 */
@RestController
@RequestMapping("/api/v1/_meta")
@Validated
public class MetaController {

    private final ChronomartProperties props;
    private final ContainerAllowList allowList;
    private final CosmosAsyncDatabase database;
    private final DiagnosticsRecorder diagnostics;
    private final com.chronomart.repo.VectorContainerStatus vectorStatus;

    @Value("${chronomart.build.version:0.1.0-SNAPSHOT}")
    private String buildVersion;

    public MetaController(ChronomartProperties props,
                          ContainerAllowList allowList,
                          CosmosAsyncDatabase database,
                          DiagnosticsRecorder diagnostics,
                          com.chronomart.repo.VectorContainerStatus vectorStatus) {
        this.props = props;
        this.allowList = allowList;
        this.database = database;
        this.diagnostics = diagnostics;
        this.vectorStatus = vectorStatus;
    }

    @GetMapping("/capabilities")
    public CapabilityManifest capabilities() {
        Map<String, Object> features = new LinkedHashMap<>();
        // PR8 lights up vector search (gated on the ProductVectors container actually
        // having been provisioned at startup — the harness must not lie about features
        // it cannot deliver). PR7 wired diagnostics + feed-ranges + cache inspection.
        // Change feed pull landed in PR6; bulk/batch/patch in PR5; HPK + TTL in PR4.
        features.put("pointCrud", true);
        features.put("queries", true);
        features.put("queriesCrossPartition", true);
        features.put("continuationTokens", true);
        features.put("bulk", true);
        features.put("transactionalBatch", true);
        features.put("changeFeedPull", true);
        features.put("changeFeedProcessor", false);
        features.put("hierarchicalPk", true);
        features.put("ttl", true);
        features.put("patch", true);
        features.put("vectorSearch", vectorStatus.isReady());
        features.put("fullTextSearch", false);
        features.put("feedRanges", true);
        // "all" = handler fires on every op (zero thresholds). "threshold" would mean
        // capture-on-violation. "none" means no recorder wired.
        features.put("diagnostics", "all");
        features.put("cacheInspection", true);
        features.put("workloads", com.chronomart.repo.WorkloadEngine.KNOWN_OPS);

        Map<String, Object> limits = Map.of(
            "maxBulkItems", 100,
            "maxBatchItems", 100,
            "maxQueryPageSize", 1000,
            "maxDiagnosticsEntries", DiagnosticsRecorder.CAP
        );

        String cosmosVersion = Objects.requireNonNullElse(
            com.azure.cosmos.CosmosAsyncClient.class.getPackage().getImplementationVersion(),
            "unknown");

        return new CapabilityManifest(
            "java",
            "chronomart-java/" + buildVersion + " azure-cosmos/" + cosmosVersion,
            List.of("v1"),
            features,
            limits,
            // synthetic-hash-1024 reflects EmbeddingGenerator's deterministic algorithm.
            // It is NOT a semantic embedding model — same seed → identical vector → top-1
            // distance ≈ 0. Suitable for testing the SDK wire path; not for recall quality.
            vectorStatus.isReady() ? "synthetic-hash-1024 (test-only, no semantic meaning)" : "none"
        );
    }

    /**
     * GET /api/v1/_meta/diagnostics?last=N — return at most N (default 50, max 1000) of the
     * most-recent Cosmos operations captured by the SDK diagnostics handler. See
     * {@link DiagnosticsRecorder} for the ring-buffer semantics and noise-filtering rules.
     */
    @GetMapping("/diagnostics")
    public List<DiagnosticsEntry> diagnostics(
        @RequestParam(name = "last", required = false, defaultValue = "50")
        @Min(1) @Max(1000) Integer last
    ) {
        return diagnostics.last(last);
    }

    /**
     * GET /api/v1/_meta/feed-ranges?container=X — list the SDK-known feed ranges for an
     * allow-listed container. Each range exposes its opaque token; the id/min/max fields
     * stay {@code null} until we add internal-SDK introspection.
     */
    @GetMapping("/feed-ranges")
    public Mono<List<FeedRangeDto>> feedRanges(@RequestParam("container") @NotBlank String container) {
        try {
            allowList.requireAllowed(container);
        } catch (IllegalArgumentException e) {
            return Mono.error(e);
        }
        return database.getContainer(container).getFeedRanges()
            .map(list -> list.stream()
                .map(fr -> new FeedRangeDto(null, null, null, fr.toString()))
                .toList());
    }

    /**
     * GET /api/v1/_meta/caches — best-effort snapshot of the SDK's container + PK-range
     * caches. The SDK doesn't expose its internal caches publicly, so we derive this by
     * reading each allow-listed container and listing its feed ranges. Per-container
     * read failures (e.g. {@code ProductVectors} pre-PR8) become rows with {@code error}
     * populated rather than silent omissions, so operators can see what's actually
     * provisioned vs. allow-listed.
     */
    @GetMapping("/caches")
    public Mono<CacheSnapshot> caches() {
        List<String> containers = new ArrayList<>(allowList.names());
        return Flux.fromIterable(containers)
            .flatMap(name -> database.getContainer(name).read()
                .map(r -> new ContainerCacheEntry(
                    props.database(), name, r.getProperties().getResourceId(),
                    Instant.now(), null))
                .onErrorResume(ex -> Mono.just(new ContainerCacheEntry(
                    props.database(), name, null, Instant.now(), ex.getMessage()))))
            .collectList()
            .flatMap(containerRows -> Flux.fromIterable(containerRows)
                .filter(row -> row.rid() != null)
                .flatMap(row -> database.getContainer(row.container()).getFeedRanges()
                    .map(frs -> new PkRangeCacheEntry(
                        row.rid(),
                        frs.stream()
                            .map(fr -> new RangeEntry(null, null, null, fr.toString()))
                            .toList()))
                    .onErrorResume(ex -> Mono.empty()))
                .collectList()
                .map(pkRows -> new CacheSnapshot(pkRows, containerRows)));
    }
}

