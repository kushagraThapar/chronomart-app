package com.chronomart.repo;

import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosChangeFeedRequestOptions;
import com.azure.cosmos.models.FeedRange;
import com.azure.cosmos.models.PartitionKey;
import com.chronomart.web.dto.ChangeFeedPullRequest;
import com.chronomart.web.dto.ChangeFeedPullResponse;
import com.chronomart.web.dto.FeedRangeDto;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs one page of pull-mode change feed for an allow-listed container.
 *
 * <p>Scope-selector handling — exactly one of {@code partitionKey} / {@code feedRange} /
 * neither (= full container) is permitted for {@code startFrom in {beginning, now}}. For
 * {@code startFrom=continuation}, the FeedRange is encoded inside the continuation token
 * so callers must NOT supply {@code partitionKey} or {@code feedRange} again — the SDK's
 * {@code createForProcessingFromContinuation(String)} does not accept a separate FR.
 *
 * <p>Semantics — change feed is a tail-pulling iterator. An empty page is a normal
 * "caught up" state, surfaced by Cosmos as HTTP 304. We translate that into a successful
 * response with {@code notModified=true} so polling clients don't see false errors.
 *
 * <p>Safety rails reused via {@link ContainerAllowList}: container allow-list +
 * partition-key parsing (including HPK levels).
 */
@Component
public class ChangeFeedRunner {

    private final CosmosAsyncDatabase database;
    private final ContainerAllowList allowList;

    public ChangeFeedRunner(CosmosAsyncDatabase database, ContainerAllowList allowList) {
        this.database = database;
        this.allowList = allowList;
    }

    public Mono<ChangeFeedPullResponse> run(ChangeFeedPullRequest req) {
        CosmosChangeFeedRequestOptions opts;
        try {
            allowList.requireAllowed(req.container());
            opts = buildOptions(req);
        } catch (IllegalArgumentException e) {
            return Mono.error(e);
        }

        opts.setMaxItemCount(req.effectivePageSize());

        return database.getContainer(req.container())
            .queryChangeFeed(opts, Object.class)
            .byPage(req.effectivePageSize())
            .next()
            .map(page -> new ChangeFeedPullResponse(
                new ArrayList<>(page.getResults()),
                page.getContinuationToken(),
                page.getResults().isEmpty() ? Boolean.TRUE : null,
                page.getRequestCharge()))
            // queryChangeFeed never emits an empty Flux for a healthy response — even the
            // caught-up state surfaces as a FeedResponse with empty results + a fresh
            // continuation. Keep this fallback as a defensive last-resort: if the SDK ever
            // does emit empty for HTTP 304 instead of throwing, callers still get a sane
            // continuation-less notModified response rather than a NoSuchElementException.
            .defaultIfEmpty(new ChangeFeedPullResponse(List.of(), null, Boolean.TRUE, 0.0))
            .onErrorResume(CosmosException.class, ex -> {
                if (ex.getStatusCode() == 304) {
                    // Some emulator/service builds surface a caught-up feed as a 304
                    // exception rather than an empty page. Translate to a notModified
                    // response; we cannot recover the continuation token from the
                    // exception so the caller should reuse whatever they sent in.
                    return Mono.just(new ChangeFeedPullResponse(
                        List.of(), null, Boolean.TRUE, (double) ex.getRequestCharge()));
                }
                return Mono.error(ex);
            });
    }

    private CosmosChangeFeedRequestOptions buildOptions(ChangeFeedPullRequest req) {
        String startFrom = req.effectiveStartFrom();
        boolean hasCont = req.continuation() != null && !req.continuation().isBlank();
        boolean hasPk   = req.partitionKey() != null;
        boolean hasFr   = req.feedRange() != null;

        if ("continuation".equals(startFrom)) {
            if (!hasCont) {
                throw new IllegalArgumentException(
                    "startFrom=continuation requires a non-empty continuation token");
            }
            if (hasPk || hasFr) {
                throw new IllegalArgumentException(
                    "startFrom=continuation must not be combined with partitionKey or "
                        + "feedRange — the feed range is encoded inside the continuation token");
            }
            return CosmosChangeFeedRequestOptions.createForProcessingFromContinuation(
                req.continuation());
        }

        if (hasCont) {
            throw new IllegalArgumentException(
                "continuation is only valid with startFrom=continuation; got startFrom="
                    + startFrom);
        }
        if (hasPk && hasFr) {
            throw new IllegalArgumentException(
                "partitionKey and feedRange are mutually exclusive — supply at most one");
        }

        FeedRange fr = resolveFeedRange(req, hasPk, hasFr);
        return switch (startFrom) {
            case "beginning" -> CosmosChangeFeedRequestOptions.createForProcessingFromBeginning(fr);
            case "now"      -> CosmosChangeFeedRequestOptions.createForProcessingFromNow(fr);
            default -> throw new IllegalArgumentException(
                "startFrom must be one of: beginning, now, continuation (got " + startFrom + ")");
        };
    }

    private FeedRange resolveFeedRange(ChangeFeedPullRequest req, boolean hasPk, boolean hasFr) {
        if (hasFr) {
            FeedRangeDto frDto = req.feedRange();
            // Only opaque round-trips through the SDK today. Reject the other fields so
            // callers don't believe they're scoping when in fact they're being silently
            // ignored. id/min/max become first-class once /_meta/feed-ranges lands (PR8).
            if (frDto.id() != null || frDto.minInclusive() != null || frDto.maxExclusive() != null) {
                throw new IllegalArgumentException(
                    "feedRange.id/minInclusive/maxExclusive are not yet supported — supply "
                        + "feedRange.opaque only (token from a prior change feed continuation "
                        + "or future /_meta/feed-ranges response)");
            }
            if (frDto.opaque() == null || frDto.opaque().isBlank()) {
                throw new IllegalArgumentException(
                    "feedRange.opaque must be a non-empty token");
            }
            try {
                return FeedRange.fromString(frDto.opaque());
            } catch (RuntimeException e) {
                // SDK can throw on raw bytes — strip the message to its class name to avoid
                // leaking decoded binary garbage into the error envelope.
                throw new IllegalArgumentException(
                    "feedRange.opaque is not a valid feed range token (" + e.getClass().getSimpleName() + ")");
            }
        }
        if (hasPk) {
            PartitionKey pk = allowList.parseRequired(req.partitionKey());
            return FeedRange.forLogicalPartition(pk);
        }
        return FeedRange.forFullRange();
    }
}
