package com.chronomart.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Mirrors {@code contracts/openapi.yaml#/components/schemas/ChangeFeedPullRequest}.
 *
 * <p>{@code partitionKey} accepts {@code String}, an array of strings/numbers/booleans
 * (hierarchical PK levels), or {@code null} — parsed via {@code ContainerAllowList.parse}.
 *
 * <p>Cross-field validation (mutually exclusive scope selectors, continuation only with
 * {@code startFrom=continuation}, etc.) lives in the runner so error messages can reference
 * the specific combination, not just "invalid".
 */
@JsonInclude(Include.NON_NULL)
public record ChangeFeedPullRequest(
    @NotBlank String container,
    @Pattern(regexp = "beginning|now|continuation",
        message = "startFrom must be one of: beginning, now, continuation")
    String startFrom,
    String continuation,
    @JsonAlias({"pk"}) Object partitionKey,
    FeedRangeDto feedRange,
    @Min(1) @Max(1000) Integer pageSize
) {
    public String effectiveStartFrom() {
        return startFrom == null ? "now" : startFrom;
    }

    public int effectivePageSize() {
        return pageSize == null ? 100 : pageSize;
    }
}
