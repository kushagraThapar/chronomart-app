package com.chronomart.web;

import com.chronomart.repo.ChangeFeedRunner;
import com.chronomart.web.dto.ChangeFeedPullRequest;
import com.chronomart.web.dto.ChangeFeedPullResponse;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * POST /api/v1/changefeed/pull — pull-mode change feed iteration.
 *
 * <p>One page per call. The caller is expected to persist the returned continuation token
 * and pass it back on the next call (with {@code startFrom=continuation}) to resume.
 * See {@link ChangeFeedRunner} for scope-selector / startFrom validation.
 */
@RestController
@RequestMapping("/api/v1/changefeed")
@Validated
public class ChangeFeedController {

    private final ChangeFeedRunner runner;

    public ChangeFeedController(ChangeFeedRunner runner) {
        this.runner = runner;
    }

    @PostMapping("/pull")
    public Mono<ChangeFeedPullResponse> pull(@Valid @RequestBody ChangeFeedPullRequest req) {
        return runner.run(req);
    }
}
