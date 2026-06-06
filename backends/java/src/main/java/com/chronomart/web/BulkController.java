package com.chronomart.web;

import com.chronomart.repo.BulkRunner;
import com.chronomart.web.dto.BulkRequest;
import com.chronomart.web.dto.BulkResponse;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * POST /api/v1/bulk — non-transactional bulk write/upsert/replace/delete against a single
 * allow-listed container. Per-item statuses are returned even if some operations failed,
 * so a 200 here just means the request was well-formed.
 *
 * <p>Cosmos contract enforced by {@link BulkRunner}: max 100 ops per request,
 * {@code maxConcurrency} ∈ {@code {null, -1, 1..5}}.
 */
@RestController
@RequestMapping("/api/v1/bulk")
@Validated
public class BulkController {

    private final BulkRunner runner;

    public BulkController(BulkRunner runner) {
        this.runner = runner;
    }

    @PostMapping
    public Mono<BulkResponse> bulk(@Valid @RequestBody BulkRequest body) {
        return runner.run(body);
    }
}
