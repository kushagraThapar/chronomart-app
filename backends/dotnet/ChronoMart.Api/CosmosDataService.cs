using System.Diagnostics;
using System.Net;
using System.Text.Json;
using Microsoft.Azure.Cosmos;

namespace ChronoMart.Api;

public sealed class CosmosDataService(
    CosmosClient client,
    ChronoMartOptions options,
    RequestCosmosMetrics requestMetrics,
    DiagnosticsRecorder diagnostics)
{
    private const int ReadSessionNotAvailableSubStatus = 1002;

    private Database Database => client.GetDatabase(options.Database);

    public async Task<T?> ReadAsync<T>(
        string containerName,
        string id,
        PartitionKey partitionKey,
        CancellationToken cancellationToken)
    {
        var watch = Stopwatch.StartNew();
        try
        {
            ItemResponse<T> response = await Container(containerName).ReadItemAsync<T>(
                id, partitionKey, cancellationToken: cancellationToken);
            Capture($"read:{containerName}", response.Headers, response.Diagnostics, watch, (int)response.StatusCode);
            return response.Resource;
        }
        catch (CosmosException exception) when (
            exception.StatusCode == HttpStatusCode.NotFound
            && exception.SubStatusCode != ReadSessionNotAvailableSubStatus)
        {
            Capture($"read:{containerName}", exception, watch);
            return default;
        }
    }

    public async Task<T> CreateAsync<T>(
        string containerName,
        T item,
        CancellationToken cancellationToken)
    {
        var watch = Stopwatch.StartNew();
        ItemResponse<T> response = await Container(containerName).CreateItemAsync(
            item, cancellationToken: cancellationToken);
        Capture($"create:{containerName}", response.Headers, response.Diagnostics, watch, (int)response.StatusCode);
        return response.Resource;
    }

    public async Task<T> UpsertAsync<T>(
        string containerName,
        T item,
        CancellationToken cancellationToken)
    {
        var watch = Stopwatch.StartNew();
        ItemResponse<T> response = await Container(containerName).UpsertItemAsync(
            item, cancellationToken: cancellationToken);
        Capture($"upsert:{containerName}", response.Headers, response.Diagnostics, watch, (int)response.StatusCode);
        return response.Resource;
    }

    public async Task DeleteAsync(
        string containerName,
        string id,
        PartitionKey partitionKey,
        CancellationToken cancellationToken)
    {
        var watch = Stopwatch.StartNew();
        try
        {
            ItemResponse<object> response = await Container(containerName).DeleteItemAsync<object>(
                id, partitionKey, cancellationToken: cancellationToken);
            Capture($"delete:{containerName}", response.Headers, response.Diagnostics, watch, (int)response.StatusCode);
        }
        catch (CosmosException exception) when (
            exception.StatusCode == HttpStatusCode.NotFound
            && exception.SubStatusCode != ReadSessionNotAvailableSubStatus)
        {
            Capture($"delete:{containerName}", exception, watch);
        }
    }

    public async Task<PageResponse<T>> QueryPageAsync<T>(
        string containerName,
        QueryDefinition query,
        PartitionKey? partitionKey,
        int pageSize,
        string? continuation,
        int maxConcurrency,
        CancellationToken cancellationToken)
    {
        var watch = Stopwatch.StartNew();
        var requestOptions = new QueryRequestOptions
        {
            MaxItemCount = pageSize,
            MaxConcurrency = maxConcurrency
        };
        if (partitionKey is not null)
        {
            requestOptions.PartitionKey = partitionKey;
        }

        using FeedIterator<T> iterator = Container(containerName)
            .GetItemQueryIterator<T>(query, continuation, requestOptions);
        if (!iterator.HasMoreResults)
        {
            return new PageResponse<T>([], null);
        }

        FeedResponse<T> response = await iterator.ReadNextAsync(cancellationToken);
        Capture($"query:{containerName}", response.Headers, response.Diagnostics, watch, (int)response.StatusCode);
        return new PageResponse<T>(response.Resource.ToArray(), response.ContinuationToken);
    }

    public async Task<QueryResponse> RunQueryAsync(QueryRequest request, CancellationToken cancellationToken)
    {
        ContractValidation.AllowedContainer(request.Container);
        ContractValidation.Required(request.Query, "query", 16_384);
        PartitionKey? partitionKey = PartitionKeys.Parse(request.PartitionKey, required: false);
        if (partitionKey is null && request.EnableCrossPartition != true)
        {
            throw ContractValidation.BadRequest(
                "partitionKey is required unless enableCrossPartition=true.");
        }

        int pageSize = ContractValidation.Bounded(request.PageSize, 100, 1, 1000, "pageSize");
        int maxConcurrency = request.MaxConcurrency ?? -1;
        if (maxConcurrency < -1 || maxConcurrency > 100)
        {
            throw ContractValidation.BadRequest("maxConcurrency must be -1 or in [0, 100].");
        }

        var query = new QueryDefinition(request.Query);
        var names = new HashSet<string>(StringComparer.Ordinal);
        foreach (QueryParameter parameter in request.Parameters ?? [])
        {
            string name = ContractValidation.Required(parameter.Name, "parameter.name", 128);
            name = name.StartsWith('@') ? name : $"@{name}";
            if (!names.Add(name))
            {
                throw ContractValidation.BadRequest($"duplicate query parameter: {name}");
            }
            query.WithParameter(name, ToObject(parameter.Value));
        }

        var watch = Stopwatch.StartNew();
        var queryOptions = new QueryRequestOptions
        {
            PartitionKey = partitionKey,
            MaxItemCount = pageSize,
            MaxConcurrency = maxConcurrency
        };
        using FeedIterator<JsonElement> iterator = Container(request.Container)
            .GetItemQueryIterator<JsonElement>(query, request.Continuation, queryOptions);
        FeedResponse<JsonElement> response = await iterator.ReadNextAsync(cancellationToken);
        Capture($"query:{request.Container}", response.Headers, response.Diagnostics, watch, (int)response.StatusCode);
        return new QueryResponse(
            response.Resource.ToArray(),
            response.ContinuationToken,
            response.RequestCharge,
            DiagnosticsObject(response.Diagnostics));
    }

    public async Task<BulkResponse> BulkAsync(BulkRequest request, CancellationToken cancellationToken)
    {
        ContractValidation.AllowedContainer(request.Container);
        if (request.Operations is null || request.Operations.Count is < 1 or > 100)
        {
            throw ContractValidation.BadRequest("operations must contain between 1 and 100 items.");
        }
        int concurrency = ContractValidation.Bounded(request.MaxConcurrency, 10, 1, 50, "maxConcurrency");

        var results = new BulkResultItem[request.Operations.Count];
        await Parallel.ForEachAsync(
            Enumerable.Range(0, request.Operations.Count),
            new ParallelOptions { MaxDegreeOfParallelism = concurrency, CancellationToken = cancellationToken },
            async (index, token) =>
            {
                BulkOperation operation = request.Operations[index];
                try
                {
                    results[index] = await ExecuteBulkOperationAsync(
                        request.Container, operation, token);
                }
                catch (CosmosException exception)
                {
                    requestMetrics.Capture(exception);
                    results[index] = new BulkResultItem(
                        operation.Op,
                        (int)exception.StatusCode,
                        exception.RequestCharge,
                        ResolveId(operation),
                        CleanOperationError(exception.Message));
                }
                catch (ApiException exception)
                {
                    results[index] = new BulkResultItem(
                        operation.Op,
                        exception.StatusCode,
                        0,
                        ResolveId(operation),
                        exception.Message);
                }
            });

        return new BulkResponse(results, results.Sum(result => result.RequestCharge));
    }

    public async Task<BatchResponse> BatchAsync(BatchRequest request, CancellationToken cancellationToken)
    {
        ContractValidation.AllowedContainer(request.Container);
        if (request.Operations is null || request.Operations.Count is < 1 or > 100)
        {
            throw ContractValidation.BadRequest("operations must contain between 1 and 100 items.");
        }

        PartitionKey partitionKey = PartitionKeys.Parse(request.PartitionKey, required: true)!.Value;
        TransactionalBatch batch = Container(request.Container).CreateTransactionalBatch(partitionKey);
        foreach (BatchOperation operation in request.Operations)
        {
            if (operation.PartitionKey is { ValueKind: not JsonValueKind.Null and not JsonValueKind.Undefined })
            {
                throw ContractValidation.BadRequest(
                    "per-op partitionKey is not allowed in a transactional batch.");
            }
            AddBatchOperation(batch, operation);
        }

        var watch = Stopwatch.StartNew();
        using TransactionalBatchResponse response = await batch.ExecuteAsync(cancellationToken);
        Capture($"batch:{request.Container}", response.Headers, response.Diagnostics, watch, (int)response.StatusCode);
        var rows = new List<BulkResultItem>(response.Count);
        for (int index = 0; index < response.Count; index++)
        {
            TransactionalBatchOperationResult result = response[index];
            BatchOperation requested = request.Operations[index];
            rows.Add(new BulkResultItem(
                requested.Op,
                (int)result.StatusCode,
                0,
                ResolveId(requested),
                result.IsSuccessStatusCode ? null : $"status {(int)result.StatusCode}"));
        }

        return new BatchResponse(
            (int)response.StatusCode,
            response.IsSuccessStatusCode,
            rows,
            response.RequestCharge);
    }

    public async Task<JsonElement> PatchAsync(PatchRequest request, CancellationToken cancellationToken)
    {
        ContractValidation.AllowedContainer(request.Container);
        ContractValidation.Required(request.Id, "id");
        if (request.Operations is null || request.Operations.Count is < 1 or > 10)
        {
            throw ContractValidation.BadRequest("patch operations must contain between 1 and 10 items.");
        }

        PartitionKey partitionKey = PartitionKeys.Parse(request.PartitionKey, required: true)!.Value;
        IReadOnlyList<PatchOperation> operations = request.Operations.Select(BuildPatchOperation).ToArray();
        var requestOptions = new PatchItemRequestOptions
        {
            IfMatchEtag = string.IsNullOrWhiteSpace(request.IfMatchEtag) ? null : request.IfMatchEtag,
            FilterPredicate = string.IsNullOrWhiteSpace(request.FilterPredicate) ? null : request.FilterPredicate
        };

        var watch = Stopwatch.StartNew();
        ItemResponse<JsonElement> response = await Container(request.Container).PatchItemAsync<JsonElement>(
            request.Id, partitionKey, operations, requestOptions, cancellationToken);
        Capture($"patch:{request.Container}", response.Headers, response.Diagnostics, watch, (int)response.StatusCode);
        return response.Resource;
    }

    public async Task<ChangeFeedPullResponse> PullChangeFeedAsync(
        ChangeFeedPullRequest request,
        CancellationToken cancellationToken)
    {
        ContractValidation.AllowedContainer(request.Container);
        int pageSize = ContractValidation.Bounded(request.PageSize, 100, 1, 1000, "pageSize");
        string startFrom = string.IsNullOrWhiteSpace(request.StartFrom) ? "now" : request.StartFrom;
        bool hasContinuation = !string.IsNullOrWhiteSpace(request.Continuation);
        bool hasPartitionKey = request.PartitionKey is { ValueKind: not JsonValueKind.Null and not JsonValueKind.Undefined };
        bool hasFeedRange = request.FeedRange is not null;

        ChangeFeedStartFrom start;
        if (startFrom == "continuation")
        {
            if (!hasContinuation)
            {
                throw ContractValidation.BadRequest(
                    "startFrom=continuation requires a non-empty continuation token.");
            }
            if (hasPartitionKey || hasFeedRange)
            {
                throw ContractValidation.BadRequest(
                    "startFrom=continuation must not be combined with partitionKey or feedRange.");
            }
            start = ChangeFeedStartFrom.ContinuationToken(request.Continuation!);
        }
        else
        {
            if (hasContinuation)
            {
                throw ContractValidation.BadRequest(
                    "continuation is only valid with startFrom=continuation.");
            }
            if (hasPartitionKey && hasFeedRange)
            {
                throw ContractValidation.BadRequest(
                    "partitionKey and feedRange are mutually exclusive.");
            }
            FeedRange? range = ResolveFeedRange(request);
            start = startFrom switch
            {
                "beginning" => range is null
                    ? ChangeFeedStartFrom.Beginning()
                    : ChangeFeedStartFrom.Beginning(range),
                "now" => range is null
                    ? ChangeFeedStartFrom.Now()
                    : ChangeFeedStartFrom.Now(range),
                _ => throw ContractValidation.BadRequest(
                    "startFrom must be one of: beginning, now, continuation.")
            };
        }

        var iteratorOptions = new ChangeFeedRequestOptions { PageSizeHint = pageSize };
        using FeedIterator<JsonElement> iterator = Container(request.Container)
            .GetChangeFeedIterator<JsonElement>(start, ChangeFeedMode.Incremental, iteratorOptions);
        var watch = Stopwatch.StartNew();
        try
        {
            FeedResponse<JsonElement> response = await iterator.ReadNextAsync(cancellationToken);
            Capture($"changefeed:{request.Container}", response.Headers, response.Diagnostics, watch,
                (int)response.StatusCode);
            return new ChangeFeedPullResponse(
                response.Resource.ToArray(),
                response.ContinuationToken,
                response.Count == 0,
                response.RequestCharge);
        }
        catch (CosmosException exception) when (exception.StatusCode == HttpStatusCode.NotModified)
        {
            Capture($"changefeed:{request.Container}", exception, watch);
            string? continuation = exception.Headers?.ContinuationToken ?? request.Continuation;
            if (string.IsNullOrWhiteSpace(continuation))
            {
                throw new InvalidOperationException(
                    "Cosmos returned 304 Not Modified without a continuation token.");
            }
            return new ChangeFeedPullResponse([], continuation, true, exception.RequestCharge);
        }
    }

    public async Task<VectorSearchResponse> VectorSearchAsync(
        VectorSearchRequest request,
        CancellationToken cancellationToken)
    {
        ContractValidation.AllowedContainer(request.Container);
        if (request.Container != ContainerNames.ProductVectors)
        {
            throw ContractValidation.BadRequest(
                $"vector search is only supported on {ContainerNames.ProductVectors}.");
        }
        if (request.Vector is null || request.Vector.Count != CosmosProvisioner.VectorDimensions)
        {
            throw ContractValidation.BadRequest(
                $"vector must contain exactly {CosmosProvisioner.VectorDimensions} float32 values.");
        }
        int k = ContractValidation.Bounded(request.K, 10, 1, 100, "k");

        var query = new QueryDefinition(
            "SELECT TOP @k c.id, c.productId, c.sellerId, c.name, " +
            "VectorDistance(c.embedding, @vector) AS score, c AS document " +
            "FROM c ORDER BY VectorDistance(c.embedding, @vector)")
            .WithParameter("@k", k)
            .WithParameter("@vector", request.Vector);
        var watch = Stopwatch.StartNew();
        using FeedIterator<JsonElement> iterator = Container(request.Container)
            .GetItemQueryIterator<JsonElement>(
                query,
                requestOptions: new QueryRequestOptions { MaxItemCount = k });
        FeedResponse<JsonElement> response = await iterator.ReadNextAsync(cancellationToken);
        Capture($"vector:{request.Container}", response.Headers, response.Diagnostics, watch, (int)response.StatusCode);

        var matches = new List<VectorMatch>(response.Count);
        foreach (JsonElement row in response)
        {
            JsonElement document = row.TryGetProperty("document", out var doc) ? doc.Clone() : row.Clone();
            matches.Add(new VectorMatch(
                GetString(row, "id"),
                GetString(row, "productId"),
                GetString(row, "sellerId"),
                GetString(row, "name"),
                row.TryGetProperty("score", out var score) && score.TryGetDouble(out double value) ? value : null,
                document));
        }
        return new VectorSearchResponse(matches, response.RequestCharge);
    }

    public async Task<IReadOnlyList<FeedRangeDto>> FeedRangesAsync(
        string containerName,
        CancellationToken cancellationToken)
    {
        ContractValidation.AllowedContainer(containerName);
        var watch = Stopwatch.StartNew();
        IReadOnlyList<FeedRange> ranges = await Container(containerName).GetFeedRangesAsync(cancellationToken);
        diagnostics.Record(new DiagnosticsEntry(
            DateTimeOffset.UtcNow,
            $"feed-ranges:{containerName}",
            watch.Elapsed.TotalMilliseconds,
            0,
            200,
            null,
            null));
        return ranges.Select(range => new FeedRangeDto(null, null, null, range.ToJsonString())).ToArray();
    }

    public async Task<CacheSnapshot> CacheSnapshotAsync(CancellationToken cancellationToken)
    {
        var containers = new List<ContainerCacheEntry>();
        var ranges = new List<PkRangeCacheEntry>();
        foreach (string name in ContainerNames.Allowed.Order())
        {
            try
            {
                ContainerResponse response = await Container(name).ReadContainerAsync(cancellationToken: cancellationToken);
                requestMetrics.Capture(response.Headers);
                string rid = response.Resource.SelfLink ?? response.Resource.Id;
                containers.Add(new ContainerCacheEntry(
                    options.Database, name, rid, DateTimeOffset.UtcNow, null));
                IReadOnlyList<FeedRange> feedRanges = await response.Container.GetFeedRangesAsync(cancellationToken);
                ranges.Add(new PkRangeCacheEntry(
                    rid,
                    feedRanges.Select(range =>
                        new FeedRangeDto(null, null, null, range.ToJsonString())).ToArray()));
            }
            catch (CosmosException exception)
            {
                requestMetrics.Capture(exception);
                containers.Add(new ContainerCacheEntry(
                    options.Database, name, null, DateTimeOffset.UtcNow,
                    $"status {(int)exception.StatusCode}: {CleanOperationError(exception.Message)}"));
            }
        }
        return new CacheSnapshot(ranges, containers);
    }

    private async Task<BulkResultItem> ExecuteBulkOperationAsync(
        string containerName,
        BulkOperation operation,
        CancellationToken cancellationToken)
    {
        string op = ContractValidation.Required(operation.Op, "op", 16).ToLowerInvariant();
        if (op is not ("create" or "upsert" or "replace" or "delete"))
        {
            throw ContractValidation.BadRequest($"unknown bulk op: {operation.Op}");
        }

        PartitionKey partitionKey = PartitionKeys.Parse(operation.PartitionKey, required: true)!.Value;
        string? id = ResolveId(operation);
        Container container = Container(containerName);
        var watch = Stopwatch.StartNew();

        if (op == "delete")
        {
            ContractValidation.Required(id, "id");
            ItemResponse<object> deleted = await container.DeleteItemAsync<object>(
                id!, partitionKey, cancellationToken: cancellationToken);
            Capture($"bulk-delete:{containerName}", deleted.Headers, deleted.Diagnostics, watch, (int)deleted.StatusCode);
            return new BulkResultItem(op, (int)deleted.StatusCode, deleted.RequestCharge, id, null);
        }

        JsonElement document = RequireDocument(operation.Document, op);
        if (id is null)
        {
            throw ContractValidation.BadRequest($"{op} op requires id or document.id.");
        }
        if (GetString(document, "id") is string documentId && documentId != id)
        {
            throw ContractValidation.BadRequest(
                $"{op} op has conflicting id values: id='{id}' vs document.id='{documentId}'.");
        }

        ItemResponse<JsonElement> response = op switch
        {
            "create" => await container.CreateItemAsync(document, partitionKey,
                cancellationToken: cancellationToken),
            "upsert" => await container.UpsertItemAsync(document, partitionKey,
                cancellationToken: cancellationToken),
            "replace" => await container.ReplaceItemAsync(document, id, partitionKey,
                cancellationToken: cancellationToken),
            _ => throw new UnreachableException()
        };
        Capture($"bulk-{op}:{containerName}", response.Headers, response.Diagnostics, watch, (int)response.StatusCode);
        return new BulkResultItem(op, (int)response.StatusCode, response.RequestCharge, id, null);
    }

    private static void AddBatchOperation(TransactionalBatch batch, BatchOperation operation)
    {
        string op = ContractValidation.Required(operation.Op, "op", 16).ToLowerInvariant();
        var requestOptions = new TransactionalBatchItemRequestOptions
        {
            IfMatchEtag = string.IsNullOrWhiteSpace(operation.IfMatchEtag)
                ? null
                : operation.IfMatchEtag
        };
        string? id = ResolveId(operation);
        switch (op)
        {
            case "create":
                batch.CreateItem(RequireDocument(operation.Document, op), requestOptions);
                break;
            case "upsert":
                batch.UpsertItem(RequireDocument(operation.Document, op), requestOptions);
                break;
            case "replace":
                ContractValidation.Required(id, "id");
                batch.ReplaceItem(id!, RequireDocument(operation.Document, op), requestOptions);
                break;
            case "delete":
                ContractValidation.Required(id, "id");
                batch.DeleteItem(id!, requestOptions);
                break;
            default:
                throw ContractValidation.BadRequest($"unknown batch op: {operation.Op}");
        }
    }

    private static PatchOperation BuildPatchOperation(PatchOperationDto operation)
    {
        string op = ContractValidation.Required(operation.Op, "op", 16).ToLowerInvariant();
        string path = ContractValidation.Required(operation.Path, "path", 1024);
        if (!path.StartsWith('/'))
        {
            throw ContractValidation.BadRequest("patch path must start with '/'.");
        }

        return op switch
        {
            "add" => PatchOperation.Add(path, RequirePatchValue(operation, op)),
            "set" => PatchOperation.Set(path, RequirePatchValue(operation, op)),
            "replace" => PatchOperation.Replace(path, RequirePatchValue(operation, op)),
            "remove" when operation.Value is null => PatchOperation.Remove(path),
            "increment" => Increment(path, RequirePatchValue(operation, op)),
            "move" when operation.Value is null && !string.IsNullOrWhiteSpace(operation.From)
                => PatchOperation.Move(operation.From!, path),
            "remove" => throw ContractValidation.BadRequest("remove op must not carry a value."),
            "move" => throw ContractValidation.BadRequest(
                "move op requires a non-blank 'from' and must not carry a value."),
            _ => throw ContractValidation.BadRequest($"unknown patch op: {operation.Op}")
        };
    }

    private static PatchOperation Increment(string path, JsonElement value)
    {
        if (value.ValueKind != JsonValueKind.Number)
        {
            throw ContractValidation.BadRequest("increment value must be numeric.");
        }
        return value.TryGetInt64(out long integer)
            ? PatchOperation.Increment(path, integer)
            : PatchOperation.Increment(path, value.GetDouble());
    }

    private static JsonElement RequirePatchValue(PatchOperationDto operation, string op)
    {
        if (operation.Value is null || operation.Value.Value.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined)
        {
            throw ContractValidation.BadRequest($"{op} op requires a value.");
        }
        return operation.Value.Value;
    }

    private static FeedRange? ResolveFeedRange(ChangeFeedPullRequest request)
    {
        if (request.PartitionKey is { ValueKind: not JsonValueKind.Null and not JsonValueKind.Undefined })
        {
            return FeedRange.FromPartitionKey(PartitionKeys.Parse(request.PartitionKey, required: true)!.Value);
        }
        if (request.FeedRange is null)
        {
            return null;
        }
        if (request.FeedRange.Id is not null
            || request.FeedRange.MinInclusive is not null
            || request.FeedRange.MaxExclusive is not null)
        {
            throw ContractValidation.BadRequest(
                "feedRange.id/minInclusive/maxExclusive are not supported; supply opaque only.");
        }
        string opaque = ContractValidation.Required(request.FeedRange.Opaque, "feedRange.opaque", 32_768);
        try
        {
            return FeedRange.FromJsonString(opaque);
        }
        catch (Exception exception) when (exception is ArgumentException or FormatException)
        {
            throw ContractValidation.BadRequest("feedRange.opaque is not a valid feed range token.");
        }
    }

    private Container Container(string name) => Database.GetContainer(name);

    private void Capture(
        string operation,
        Headers headers,
        CosmosDiagnostics? cosmosDiagnostics,
        Stopwatch watch,
        int statusCode)
    {
        requestMetrics.Capture(headers);
        diagnostics.Record(new DiagnosticsEntry(
            DateTimeOffset.UtcNow,
            operation,
            watch.Elapsed.TotalMilliseconds,
            headers.RequestCharge,
            statusCode,
            headers.ActivityId,
            DiagnosticsObject(cosmosDiagnostics)));
    }

    private void Capture(string operation, CosmosException exception, Stopwatch watch)
    {
        requestMetrics.Capture(exception);
        diagnostics.Record(new DiagnosticsEntry(
            DateTimeOffset.UtcNow,
            operation,
            watch.Elapsed.TotalMilliseconds,
            exception.RequestCharge,
            (int)exception.StatusCode,
            exception.ActivityId,
            DiagnosticsObject(exception.Diagnostics)));
    }

    private static object? DiagnosticsObject(CosmosDiagnostics? value) =>
        value is null ? null : new Dictionary<string, object> { ["summary"] = value.ToString() };

    private static object? ToObject(JsonElement value) => value.ValueKind switch
    {
        JsonValueKind.Null or JsonValueKind.Undefined => null,
        JsonValueKind.String => value.GetString(),
        JsonValueKind.True or JsonValueKind.False => value.GetBoolean(),
        JsonValueKind.Number when value.TryGetInt64(out long integer) => integer,
        JsonValueKind.Number when value.TryGetDecimal(out decimal number) => number,
        JsonValueKind.Number => value.GetDouble(),
        JsonValueKind.Array => value.EnumerateArray().Select(ToObject).ToArray(),
        JsonValueKind.Object => value.EnumerateObject()
            .ToDictionary(property => property.Name, property => ToObject(property.Value)),
        _ => value.ToString()
    };

    private static JsonElement RequireDocument(JsonElement? document, string op)
    {
        if (document is null || document.Value.ValueKind != JsonValueKind.Object)
        {
            throw ContractValidation.BadRequest($"{op} op requires a document object.");
        }
        return document.Value;
    }

    private static string? ResolveId(BulkOperation operation) =>
        FirstNonBlank(operation.Id,
            operation.Document is { ValueKind: JsonValueKind.Object } document
                ? GetString(document, "id")
                : null);

    private static string? ResolveId(BatchOperation operation) =>
        FirstNonBlank(operation.Id,
            operation.Document is { ValueKind: JsonValueKind.Object } document
                ? GetString(document, "id")
                : null);

    private static string? FirstNonBlank(params string?[] values) =>
        values.FirstOrDefault(value => !string.IsNullOrWhiteSpace(value));

    private static string? GetString(JsonElement element, string property) =>
        element.ValueKind == JsonValueKind.Object
        && element.TryGetProperty(property, out JsonElement value)
        && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;

    private static string CleanOperationError(string? message) =>
        string.IsNullOrWhiteSpace(message) ? "Cosmos operation failed." : message.Split('\n')[0];
}
