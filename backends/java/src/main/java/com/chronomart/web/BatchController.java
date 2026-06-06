package com.chronomart.web;

import com.chronomart.repo.BatchRunner;
import com.chronomart.web.dto.BatchRequest;
import com.chronomart.web.dto.BatchResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * POST /api/v1/batch — transactional batch within a single partition.
 *
 * <p>On a transactional rollback we surface HTTP 409 with the full {@link BatchResponse} in
 * the body. The body's {@code statusCode} preserves the SDK's batch-level status (so 412
 * for an etag mismatch is still visible) while the HTTP envelope matches the OpenAPI
 * contract ({@code 200} on success, {@code 409} on transactional failure).
 */
@RestController
@RequestMapping("/api/v1/batch")
@Validated
public class BatchController {

    private final BatchRunner runner;

    public BatchController(BatchRunner runner) {
        this.runner = runner;
    }

    @PostMapping
    public Mono<ResponseEntity<BatchResponse>> batch(@Valid @RequestBody BatchRequest body) {
        return runner.run(body)
            .map(resp -> resp.success()
                ? ResponseEntity.ok(resp)
                : ResponseEntity.status(409).body(resp));
    }
}
