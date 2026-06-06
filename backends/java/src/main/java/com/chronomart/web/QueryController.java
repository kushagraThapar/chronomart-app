package com.chronomart.web;

import com.chronomart.repo.QueryRunner;
import com.chronomart.web.dto.QueryRequest;
import com.chronomart.web.dto.QueryResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Generic parameterised SQL query endpoint. See {@link QueryRunner} for safety rails
 * (container allow-list, cross-partition opt-in, parameterised binds, single-page exec).
 * Mirrors {@code contracts/openapi.yaml#/paths/~1queries~1run}.
 */
@RestController
@RequestMapping("/api/v1/queries")
public class QueryController {

    private final QueryRunner runner;

    public QueryController(QueryRunner runner) {
        this.runner = runner;
    }

    @PostMapping("/run")
    public Mono<QueryResponse> run(@Valid @RequestBody QueryRequest req) {
        return runner.run(req);
    }
}
