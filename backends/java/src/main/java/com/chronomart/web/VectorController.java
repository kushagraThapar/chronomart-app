package com.chronomart.web;

import com.chronomart.repo.VectorSearchRunner;
import com.chronomart.web.dto.VectorSearchRequest;
import com.chronomart.web.dto.VectorSearchResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * {@code POST /api/v1/vector/search}. Delegates to {@link VectorSearchRunner} which owns
 * the allow-list, ready-flag, dimension, and {@code k} checks. Mirrors
 * {@code contracts/openapi.yaml#/paths/~1vector~1search}.
 *
 * <p>Returns {@link HttpStatus#NOT_IMPLEMENTED} (501) — already declared in the OpenAPI
 * contract — when the {@code ProductVectors} container did not provision at startup so
 * the harness does not pretend a capability that is not wired. The error message points
 * at the diagnostics endpoint so the operator can find the original SDK failure.
 */
@RestController
@RequestMapping("/api/v1/vector")
public class VectorController {

    private final VectorSearchRunner runner;

    public VectorController(VectorSearchRunner runner) {
        this.runner = runner;
    }

    @PostMapping("/search")
    public Mono<VectorSearchResponse> search(@Valid @RequestBody VectorSearchRequest req) {
        if (!runner.isReady()) {
            return Mono.error(new ResponseStatusException(
                HttpStatus.NOT_IMPLEMENTED,
                "vector search is not available: ProductVectors was not provisioned at startup "
                    + "(see /api/v1/_meta/diagnostics for the SDK call detail)"));
        }
        return runner.search(req);
    }
}
