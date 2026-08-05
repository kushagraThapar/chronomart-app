using System.Text.Json;
using System.Text.Json.Serialization;

namespace ChronoMart.Api;

public sealed record Seller(
    string Id,
    string Name,
    string Country,
    double? Rating,
    DateTimeOffset? JoinedAt);

public sealed record Product(
    string Id,
    string SellerId,
    string? CategoryId,
    string Name,
    string? Brand,
    string? Model,
    decimal PriceUsd,
    string? Currency,
    Dictionary<string, JsonElement>? Attributes,
    IReadOnlyList<string>? Tags,
    IReadOnlyList<string>? Images,
    DateTimeOffset? CreatedAt,
    DateTimeOffset? UpdatedAt);

public sealed record Customer(
    string Id,
    string Name,
    string? Email,
    string? Country,
    string? Tier,
    DateTimeOffset? CreatedAt);

public sealed record OrderItem(string ProductId, string? SellerId, int Qty, decimal UnitPriceUsd);

public sealed record Order(
    string Id,
    string CustomerId,
    string YearMonth,
    string? Status,
    IReadOnlyList<OrderItem> Items,
    decimal TotalUsd,
    DateTimeOffset? CreatedAt,
    DateTimeOffset? ShippedAt);

public sealed record Review(
    string Id,
    string ProductId,
    string CustomerId,
    int Rating,
    string? Title,
    string? Body,
    DateTimeOffset? CreatedAt);

public sealed record CartItem(
    string ProductId,
    int Qty,
    DateTimeOffset? AddedAt,
    string? SellerId,
    decimal? UnitPriceUsd);

public sealed record Cart(
    string Id,
    string CustomerId,
    IReadOnlyList<CartItem> Items,
    DateTimeOffset? UpdatedAt,
    int? Ttl);

public sealed record Inventory(
    string Id,
    string SellerId,
    string ProductId,
    int Available,
    int? Reserved,
    DateTimeOffset? UpdatedAt);

public sealed record PageResponse<T>(IReadOnlyList<T> Items, string? Continuation);

public sealed record CapabilityManifest(
    string Sdk,
    string SdkVersion,
    IReadOnlyList<string> ApiVersions,
    IReadOnlyDictionary<string, object> Features,
    IReadOnlyDictionary<string, object> Limits,
    string VectorEmbeddingProvider);

public sealed record ErrorEnvelope(ApiError Error);

public sealed record ApiError(
    string Code,
    string Message,
    string Sdk,
    string SdkVersion,
    int? CosmosStatusCode = null,
    int? CosmosSubStatusCode = null,
    string? ActivityId = null,
    string? TraceId = null);

public sealed record DiagnosticsEntry(
    DateTimeOffset Timestamp,
    string Operation,
    double DurationMs,
    double RequestCharge,
    int StatusCode,
    string? ActivityId,
    object? Diagnostics);

public sealed record FeedRangeDto(
    string? Id,
    string? MinInclusive,
    string? MaxExclusive,
    string? Opaque);

public sealed record CacheSnapshot(
    IReadOnlyList<PkRangeCacheEntry> PkRangeCache,
    IReadOnlyList<ContainerCacheEntry> ContainerCache);

public sealed record PkRangeCacheEntry(string ContainerRid, IReadOnlyList<FeedRangeDto> Ranges);

public sealed record ContainerCacheEntry(
    string Database,
    string Container,
    string? Rid,
    DateTimeOffset SnapshotAt,
    string? Error);

public sealed record QueryParameter(string Name, JsonElement Value);

public sealed record QueryRequest(
    string Container,
    string Query,
    IReadOnlyList<QueryParameter>? Parameters,
    JsonElement? PartitionKey,
    int? PageSize,
    string? Continuation,
    bool? EnableCrossPartition,
    int? MaxConcurrency);

public sealed record QueryResponse(
    IReadOnlyList<JsonElement> Items,
    string? Continuation,
    double RequestCharge,
    object? Diagnostics);

public sealed record BulkOperation(
    string Op,
    JsonElement PartitionKey,
    JsonElement? Document,
    string? Id);

public sealed record BulkRequest(
    string Container,
    IReadOnlyList<BulkOperation> Operations,
    int? MaxConcurrency);

public sealed record BulkResultItem(
    string Op,
    int StatusCode,
    double RequestCharge,
    string? ResourceId,
    string? Error);

public sealed record BulkResponse(IReadOnlyList<BulkResultItem> Results, double TotalRequestCharge);

public sealed record BatchOperation(
    string Op,
    JsonElement? PartitionKey,
    JsonElement? Document,
    string? Id,
    string? IfMatchEtag);

public sealed record BatchRequest(
    string Container,
    JsonElement PartitionKey,
    IReadOnlyList<BatchOperation> Operations);

public sealed record BatchResponse(
    int StatusCode,
    bool Success,
    IReadOnlyList<BulkResultItem> Results,
    double RequestCharge);

public sealed record PatchOperationDto(
    string Op,
    string Path,
    JsonElement? Value,
    string? From);

public sealed record PatchRequest(
    string Container,
    string Id,
    JsonElement PartitionKey,
    string? IfMatchEtag,
    string? FilterPredicate,
    IReadOnlyList<PatchOperationDto> Operations);

public sealed record ChangeFeedPullRequest(
    string Container,
    string? StartFrom,
    string? Continuation,
    JsonElement? PartitionKey,
    FeedRangeDto? FeedRange,
    int? PageSize);

public sealed record ChangeFeedPullResponse(
    IReadOnlyList<JsonElement> Items,
    string? Continuation,
    bool? NotModified,
    double RequestCharge);

public sealed record VectorSearchRequest(string Container, IReadOnlyList<float> Vector, int? K);

public sealed record VectorMatch(
    string? Id,
    string? ProductId,
    string? SellerId,
    string? Name,
    double? Score,
    JsonElement Document);

public sealed record VectorSearchResponse(IReadOnlyList<VectorMatch> Matches, double RequestCharge);

public static class JsonDefaults
{
    public static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        PropertyNameCaseInsensitive = true
    };
}
