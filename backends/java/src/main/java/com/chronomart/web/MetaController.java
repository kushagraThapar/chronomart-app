package com.chronomart.web;

import com.chronomart.config.ChronomartProperties;
import com.chronomart.web.dto.CapabilityManifest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cross-cutting metadata endpoints under {@code /api/v1/_meta/*}.
 *
 * <p>Phase 1 / PR2 advertises only what's actually wired up: read-only sellers access.
 * Every other feature is honestly reported as {@code false} so the UI shows
 * "feature unsupported" badges. As subsequent PRs land features, this manifest is updated.
 */
@RestController
@RequestMapping("/api/v1/_meta")
public class MetaController {

    private final ChronomartProperties props;

    @Value("${chronomart.build.version:0.1.0-SNAPSHOT}")
    private String buildVersion;

    public MetaController(ChronomartProperties props) {
        this.props = props;
    }

    @GetMapping("/capabilities")
    public CapabilityManifest capabilities() {
        Map<String, Object> features = new LinkedHashMap<>();
        // PR4 wires up HPK CRUD (ProductsHpk + Orders) and Cart with per-doc TTL on top
        // of PR3's point CRUD + queries. Bulk/batch, change feed, vector search still off.
        features.put("pointCrud", true);
        features.put("queries", true);
        features.put("queriesCrossPartition", true);
        features.put("continuationTokens", true);
        features.put("bulk", false);
        features.put("transactionalBatch", false);
        features.put("changeFeedPull", false);
        features.put("changeFeedProcessor", false);
        features.put("hierarchicalPk", true);
        features.put("ttl", true);
        features.put("patch", false);
        features.put("vectorSearch", false);
        features.put("fullTextSearch", false);
        features.put("feedRanges", false);
        features.put("diagnostics", "none");
        features.put("cacheInspection", false);
        features.put("workloads", List.of());

        Map<String, Object> limits = Map.of(
            "maxBulkItems", 100,
            "maxBatchItems", 100,
            "maxQueryPageSize", 1000
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
            "none"   // deterministic-mock or ollama in PR6
        );
    }
}
