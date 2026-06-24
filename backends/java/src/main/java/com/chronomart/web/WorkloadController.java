package com.chronomart.web;

import com.chronomart.repo.WorkloadEngine;
import com.chronomart.web.dto.WorkloadAnomaly;
import com.chronomart.web.dto.WorkloadProgress;
import com.chronomart.web.dto.WorkloadSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST surface for the composable workload runner. See {@link WorkloadEngine} for the
 * concurrency model and supported ops; this controller is just shape/validation glue.
 *
 * <p>Endpoints (all under {@code /api/v1/workloads}):
 * <ul>
 *   <li>{@code POST /run} — start a new run, returns {@code {runId, name, status}}.
 *       The run is async; poll {@code GET /{runId}} for progress.</li>
 *   <li>{@code GET /{runId}} — current snapshot of one run. Returns 404 if the run
 *       has aged out of the {@link com.chronomart.repo.WorkloadRegistry} ring buffer.</li>
 *   <li>{@code GET /} — list of all runs in the ring buffer, most recent first.</li>
 *   <li>{@code GET /{runId}/anomalies} — paged correctness anomalies for a verification run
 *       (empty when the run had verification disabled).</li>
 *   <li>{@code POST /{runId}/stop} — cooperative cancellation. Returns 404 if the
 *       run is unknown; 200 with {@code {stopped: true}} otherwise.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/workloads")
@Validated
public class WorkloadController {

    /** Hard cap on a single anomalies page so a caller can't request an unbounded slice. */
    private static final int MAX_ANOMALY_PAGE = 500;

    private final WorkloadEngine engine;

    public WorkloadController(WorkloadEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(@Valid @RequestBody WorkloadSpec spec) {
        String runId = engine.start(spec);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
            "runId", runId,
            "name", spec.name(),
            "status", "RUNNING"
        ));
    }

    @GetMapping("/{runId}")
    public ResponseEntity<WorkloadProgress> progress(@PathVariable String runId) {
        WorkloadProgress p = engine.progress(runId);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }

    @GetMapping
    public List<WorkloadProgress> list() {
        return engine.list();
    }

    @GetMapping("/{runId}/anomalies")
    public ResponseEntity<List<WorkloadAnomaly>> anomalies(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(defaultValue = "100") @Min(1) @Max(MAX_ANOMALY_PAGE) int limit) {
        List<WorkloadAnomaly> page = engine.anomalies(runId, offset, limit);
        return page == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(page);
    }

    @PostMapping("/{runId}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable String runId) {
        boolean ok = engine.stop(runId);
        if (!ok) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("runId", runId, "stopped", true));
    }
}
