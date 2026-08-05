using System.Net.Security;
using System.Reflection;
using System.Text.Json;
using Microsoft.Azure.Cosmos;

namespace ChronoMart.Api;

public sealed class ChronoMartOptions
{
    public string Endpoint { get; init; } = "https://localhost:8081/";
    public string Key { get; init; } =
        "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";
    public string Database { get; init; } = "ChronoMart";
    public bool AllowInvalidCertificates { get; init; }
    public string? EmulatorHost { get; init; }
    public bool ContainerInitEnabled { get; init; } = true;
    public bool CreateVectorContainer { get; init; } = true;

    public static ChronoMartOptions FromConfiguration(IConfiguration configuration)
    {
        return new ChronoMartOptions
        {
            Endpoint = configuration["COSMOS_ENDPOINT"] ?? "https://localhost:8081/",
            Key = configuration["COSMOS_KEY"] ??
                "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==",
            Database = configuration["COSMOS_DATABASE"] ?? "ChronoMart",
            AllowInvalidCertificates = ParseBool(configuration["COSMOS_ALLOW_INVALID_CERTS"]),
            EmulatorHost = configuration["COSMOS_EMULATOR_HOST"],
            ContainerInitEnabled = ParseBool(configuration["CHRONOMART_CONTAINER_INIT_ENABLED"], true),
            CreateVectorContainer = ParseBool(configuration["CHRONOMART_CONTAINER_INIT_VECTOR"], true)
        };
    }

    private static bool ParseBool(string? value, bool fallback = false) =>
        bool.TryParse(value, out var result) ? result : fallback;
}

public static class ContainerNames
{
    public const string Sellers = "Sellers";
    public const string Products = "Products";
    public const string ProductsHpk = "ProductsHpk";
    public const string Inventory = "Inventory";
    public const string ProductVectors = "ProductVectors";
    public const string Customers = "Customers";
    public const string Orders = "Orders";
    public const string Reviews = "Reviews";
    public const string Cart = "Cart";
    public const string ChangeFeedLease = "ChangeFeedLease";

    public static readonly IReadOnlySet<string> Allowed = new HashSet<string>(
        [
            Sellers, Products, ProductsHpk, Inventory, ProductVectors,
            Customers, Orders, Reviews, Cart, ChangeFeedLease
        ],
        StringComparer.Ordinal);
}

public static class SdkInfo
{
    public static string Version { get; } =
        typeof(CosmosClient).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()
            ?.InformationalVersion.Split('+')[0]
        ?? typeof(CosmosClient).Assembly.GetName().Version?.ToString()
        ?? "unknown";

    public static string HeaderValue => $"dotnet Microsoft.Azure.Cosmos/{Version}";
}

public static class CosmosClientFactory
{
    public static CosmosClient Create(ChronoMartOptions options)
    {
        if (!Uri.TryCreate(options.Endpoint, UriKind.Absolute, out var endpoint))
        {
            throw new InvalidOperationException("COSMOS_ENDPOINT must be an absolute URI.");
        }

        bool emulator = IsEmulator(endpoint, options.EmulatorHost);
        if (options.AllowInvalidCertificates && !emulator)
        {
            throw new InvalidOperationException(
                "Refusing COSMOS_ALLOW_INVALID_CERTS for a non-emulator endpoint.");
        }

        var clientOptions = new CosmosClientOptions
        {
            AllowBulkExecution = true,
            ConnectionMode = emulator ? ConnectionMode.Gateway : ConnectionMode.Direct,
            ConsistencyLevel = ConsistencyLevel.Session,
            Serializer = new SystemTextJsonCosmosSerializer(JsonDefaults.Options),
            ApplicationName = "chronomart-dotnet",
            LimitToEndpoint = emulator
        };

        if (options.AllowInvalidCertificates)
        {
            clientOptions.ServerCertificateCustomValidationCallback =
                (_, _, errors) => errors == SslPolicyErrors.None || emulator;
        }

        return new CosmosClient(options.Endpoint, options.Key, clientOptions);
    }

    internal static bool IsEmulator(Uri endpoint, string? configuredHost)
    {
        string host = endpoint.Host;
        return host.Equals("localhost", StringComparison.OrdinalIgnoreCase)
            || host == "127.0.0.1"
            || host == "::1"
            || (!string.IsNullOrWhiteSpace(configuredHost)
                && host.Equals(configuredHost, StringComparison.OrdinalIgnoreCase));
    }
}

public sealed class SystemTextJsonCosmosSerializer(JsonSerializerOptions options) : CosmosSerializer
{
    public override T FromStream<T>(Stream stream)
    {
        using (stream)
        {
            if (typeof(Stream).IsAssignableFrom(typeof(T)))
            {
                return (T)(object)stream;
            }

            return JsonSerializer.Deserialize<T>(stream, options)
                ?? throw new JsonException($"Cosmos returned an empty {typeof(T).Name} payload.");
        }
    }

    public override Stream ToStream<T>(T input)
    {
        var stream = new MemoryStream();
        JsonSerializer.Serialize(stream, input, options);
        stream.Position = 0;
        return stream;
    }
}
