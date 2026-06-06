package com.chronomart.web;

import com.chronomart.repo.PatchRunner;
import com.chronomart.web.dto.PatchRequest;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * POST /api/v1/patch — partial update against a single document.
 *
 * <p>Supports all six Cosmos patch verbs natively (add, set, replace, remove, increment,
 * move). Returns the full patched document. Optimistic concurrency via
 * {@code ifMatchEtag} (412 on mismatch) and conditional updates via
 * {@code filterPredicate} (412 when the predicate doesn't match) are both honored.
 */
@RestController
@RequestMapping("/api/v1/patch")
@Validated
public class PatchController {

    private final PatchRunner runner;

    public PatchController(PatchRunner runner) {
        this.runner = runner;
    }

    @PostMapping
    public Mono<Map<String, Object>> patch(@Valid @RequestBody PatchRequest body) {
        return runner.run(body);
    }
}
