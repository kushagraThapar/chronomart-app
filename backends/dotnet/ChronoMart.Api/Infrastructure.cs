using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using Microsoft.Azure.Cosmos;

namespace ChronoMart.Api;

public sealed class ApiException(int statusCode, string code, string message) : Exception(message)
{
    public int StatusCode { get; } = statusCode;
    public string Code { get; } = code;
}

public sealed class RequestCosmosMetrics
{
    private readonly object gate = new();
    private double requestCharge;
    private string? activityId;

    public double RequestCharge
    {
        get { lock (gate) return requestCharge; }
    }

    public string? ActivityId
    {
        get { lock (gate) return activityId; }
    }

    public void Capture(Headers? headers)
    {
        if (headers is null)
        {
            return;
        }

        lock (gate)
        {
            requestCharge += headers.RequestCharge;
            activityId ??= headers.ActivityId;
        }
    }

    public void Capture(CosmosException exception)
    {
        lock (gate)
        {
            requestCharge += exception.RequestCharge;
            activityId ??= exception.ActivityId;
        }
    }
}

public sealed class DiagnosticsRecorder
{
    public const int Capacity = 1000;
    private readonly ConcurrentQueue<DiagnosticsEntry> entries = new();

    public void Record(DiagnosticsEntry entry)
    {
        entries.Enqueue(entry);
        while (entries.Count > Capacity)
        {
            entries.TryDequeue(out _);
        }
    }

    public IReadOnlyList<DiagnosticsEntry> Last(int count) =>
        entries.Reverse().Take(count).ToArray();
}

public sealed class VectorCapabilityState
{
    private int ready;
    public bool IsReady => Volatile.Read(ref ready) == 1;
    public void MarkReady() => Interlocked.Exchange(ref ready, 1);
}

public static class CapabilityProvider
{
    public static CapabilityManifest Create(VectorCapabilityState vectorState)
    {
        var features = new Dictionary<string, object>
        {
            ["pointCrud"] = true,
            ["queries"] = true,
            ["queriesCrossPartition"] = true,
            ["continuationTokens"] = true,
            ["bulk"] = true,
            ["transactionalBatch"] = true,
            ["changeFeedPull"] = true,
            ["changeFeedProcessor"] = false,
            ["hierarchicalPk"] = true,
            ["ttl"] = true,
            ["patch"] = true,
            ["vectorSearch"] = vectorState.IsReady,
            ["fullTextSearch"] = false,
            ["feedRanges"] = true,
            ["diagnostics"] = "full",
            ["cacheInspection"] = true,
            ["workloads"] = Array.Empty<string>()
        };
        var limits = new Dictionary<string, object>
        {
            ["maxBulkItems"] = 100,
            ["maxBatchItems"] = 100,
            ["maxQueryPageSize"] = 1000,
            ["maxDiagnosticsEntries"] = DiagnosticsRecorder.Capacity
        };
        return new CapabilityManifest(
            "dotnet",
            $"Microsoft.Azure.Cosmos/{SdkInfo.Version}",
            ["v1"],
            features,
            limits,
            vectorState.IsReady ? "caller-supplied-1024-float32" : "none");
    }
}

public sealed class StandardHeadersMiddleware(RequestDelegate next)
{
    public async Task InvokeAsync(HttpContext context, RequestCosmosMetrics cosmosMetrics)
    {
        var stopwatch = Stopwatch.StartNew();
        context.Response.OnStarting(() =>
        {
            context.Response.Headers["x-ms-request-charge"] =
                cosmosMetrics.RequestCharge.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture);
            context.Response.Headers["x-ms-activity-id"] =
                cosmosMetrics.ActivityId ?? context.TraceIdentifier;
            context.Response.Headers["x-cm-latency-ms"] =
                stopwatch.Elapsed.TotalMilliseconds.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture);
            context.Response.Headers["x-cm-sdk"] = SdkInfo.HeaderValue;
            context.Response.Headers["x-cm-trace-id"] =
                Activity.Current?.TraceId.ToHexString() ?? context.TraceIdentifier;
            return Task.CompletedTask;
        });
        try
        {
            await next(context);
        }
        finally
        {
            stopwatch.Stop();
        }
    }
}

public sealed class ErrorHandlingMiddleware(RequestDelegate next, ILogger<ErrorHandlingMiddleware> logger)
{
    public async Task InvokeAsync(HttpContext context, RequestCosmosMetrics metrics)
    {
        try
        {
            await next(context);
        }
        catch (ApiException exception)
        {
            await WriteErrorAsync(context, exception.StatusCode, exception.Code, exception.Message);
        }
        catch (BadHttpRequestException exception)
        {
            await WriteErrorAsync(context, StatusCodes.Status400BadRequest, "BadRequest", exception.Message);
        }
        catch (CosmosException exception)
        {
            metrics.Capture(exception);
            logger.LogWarning(
                "Cosmos failure status={Status} substatus={Substatus} activity={ActivityId}",
                (int)exception.StatusCode,
                exception.SubStatusCode,
                exception.ActivityId);
            await WriteErrorAsync(
                context,
                (int)exception.StatusCode,
                CodeForStatus((int)exception.StatusCode),
                CleanCosmosMessage(exception.Message),
                exception);
        }
        catch (OperationCanceledException) when (context.RequestAborted.IsCancellationRequested)
        {
            // The caller disconnected; do not attempt a response write.
        }
        catch (Exception exception)
        {
            logger.LogError(exception, "Unhandled request failure");
            await WriteErrorAsync(
                context,
                StatusCodes.Status500InternalServerError,
                "InternalError",
                "The backend could not complete the request.");
        }
    }

    private static async Task WriteErrorAsync(
        HttpContext context,
        int status,
        string code,
        string message,
        CosmosException? cosmosException = null)
    {
        if (context.Response.HasStarted)
        {
            return;
        }

        context.Response.StatusCode = status;
        context.Response.ContentType = "application/json";
        string traceId = Activity.Current?.TraceId.ToHexString() ?? context.TraceIdentifier;
        var envelope = new ErrorEnvelope(new ApiError(
            code,
            message,
            "dotnet",
            SdkInfo.Version,
            cosmosException is null ? null : (int)cosmosException.StatusCode,
            cosmosException?.SubStatusCode,
            cosmosException?.ActivityId,
            traceId));
        await context.Response.WriteAsJsonAsync(envelope, JsonDefaults.Options);
    }

    private static string CodeForStatus(int status) => status switch
    {
        400 => "BadRequest",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "NotFound",
        408 => "RequestTimeout",
        409 => "Conflict",
        412 => "PreconditionFailed",
        413 => "RequestEntityTooLarge",
        429 => "TooManyRequests",
        503 => "ServiceUnavailable",
        _ => "CosmosError"
    };

    private static string CleanCosmosMessage(string? message)
    {
        if (string.IsNullOrWhiteSpace(message))
        {
            return "Cosmos DB request failed.";
        }

        int diagnostics = message.IndexOf("CosmosDiagnostics", StringComparison.OrdinalIgnoreCase);
        return diagnostics > 0 ? message[..diagnostics].TrimEnd(' ', ',', '{') : message;
    }
}

public static class ContractValidation
{
    public static string Required(string? value, string name, int maxLength = 256)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            throw BadRequest($"{name} is required and must not be blank.");
        }
        if (value.Length > maxLength)
        {
            throw BadRequest($"{name} must be at most {maxLength} characters.");
        }
        return value;
    }

    public static int Bounded(int? value, int fallback, int minimum, int maximum, string name)
    {
        int actual = value ?? fallback;
        if (actual < minimum || actual > maximum)
        {
            throw BadRequest($"{name} must be in [{minimum}, {maximum}].");
        }
        return actual;
    }

    public static void AllowedContainer(string? container)
    {
        if (container is null || !ContainerNames.Allowed.Contains(container))
        {
            throw BadRequest(
                $"container '{container}' is not in the allow-list: {string.Join(", ", ContainerNames.Allowed.Order())}");
        }
    }

    public static void PathMatches(string routeValue, string bodyValue, string name)
    {
        Required(bodyValue, name);
        if (!string.Equals(routeValue, bodyValue, StringComparison.Ordinal))
        {
            throw BadRequest($"{name} in the body must match the route value.");
        }
    }

    public static ApiException BadRequest(string message) =>
        new(StatusCodes.Status400BadRequest, "BadRequest", message);

    public static ApiException NotFound(string resource, string id) =>
        new(StatusCodes.Status404NotFound, "NotFound", $"{resource} '{id}' was not found.");

    public static ApiException NotImplemented(string feature) =>
        new(StatusCodes.Status501NotImplemented, "NotImplemented",
            $"{feature} is not supported by the .NET backend in this phase.");
}
