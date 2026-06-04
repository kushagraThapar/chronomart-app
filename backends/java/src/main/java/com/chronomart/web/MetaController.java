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
        // Read paths are wired; writes/queries land in PR3.
        features.put("pointCrud", false);
        features.put("queries", false);
        features.put("queriesCrossPartition", false);
        features.put("bulk", false);
        features.put("transactionalBatch", false);
        features.put("changeFeedPull", false);
        features.put("changeFeedProcessor", false);
        features.put("hierarchicalPk", false);
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

        String cosmosVersion = com.azure.cosmos.CosmosAsyncClient.class.getPackage()
            .getImplementationVersion();

        return new CapabilityManifest(
            "java",
            "chronomart-java/" + buildVersion + " azure-cosmos/" + (cosmosVersion != null ? cosmosVersion : "unknown"),
            List.of("v1"),
            features,
            limits,
            "none"   // deterministic-mock or ollama in PR6
        );
    }
}
