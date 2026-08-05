using System.Collections.ObjectModel;
using Microsoft.Azure.Cosmos;

namespace ChronoMart.Api;

public sealed class CosmosProvisioner(
    CosmosClient client,
    ChronoMartOptions options,
    VectorCapabilityState vectorState,
    ILogger<CosmosProvisioner> logger) : IHostedService
{
    public const int CartTtlSeconds = 7 * 24 * 60 * 60;
    public const int VectorDimensions = 1024;

    public async Task StartAsync(CancellationToken cancellationToken)
    {
        if (!options.ContainerInitEnabled)
        {
            logger.LogInformation("Cosmos container initialization is disabled.");
            return;
        }

        Database database = (await client.CreateDatabaseIfNotExistsAsync(
            options.Database,
            cancellationToken: cancellationToken)).Database;

        await EnsureAsync(database, ContainerNames.Sellers, ["/id"], 1000, null, cancellationToken);
        await EnsureAsync(database, ContainerNames.Products, ["/sellerId"], 4000, null, cancellationToken);

        // vNext emulator parity: use the same two-level HPKs as the Java backend and
        // Compose seed. The contract's third /id leaf is deferred because three-level
        // HPK creation through the SDK currently fails against vNext; PK definitions
        // are immutable, so moving to the target shape requires drop + recreate once
        // the emulator supports it.
        await EnsureAsync(database, ContainerNames.ProductsHpk,
            PartitionKeys.ProductHpkPaths, 4000, null, cancellationToken);
        await EnsureAsync(database, ContainerNames.Inventory, ["/sellerId"], 1000, null, cancellationToken);
        await EnsureAsync(database, ContainerNames.Customers, ["/id"], 1000, null, cancellationToken);
        await EnsureAsync(database, ContainerNames.Orders,
            PartitionKeys.OrderHpkPaths, 4000, null, cancellationToken);
        await EnsureAsync(database, ContainerNames.Reviews, ["/productId"], 1000, null, cancellationToken);
        await EnsureAsync(database, ContainerNames.Cart,
            ["/customerId"], 1000, CartTtlSeconds, cancellationToken);
        await EnsureAsync(database, ContainerNames.ChangeFeedLease, ["/id"], 1000, null, cancellationToken);

        if (options.CreateVectorContainer)
        {
            await TryEnsureVectorAsync(database, cancellationToken);
        }
        else
        {
            logger.LogInformation("Vector container provisioning is disabled.");
        }
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;

    private async Task EnsureAsync(
        Database database,
        string name,
        IReadOnlyList<string> partitionKeyPaths,
        int autoscaleMaxThroughput,
        int? defaultTtl,
        CancellationToken cancellationToken)
    {
        var properties = new ContainerProperties(name, partitionKeyPaths)
        {
            DefaultTimeToLive = defaultTtl
        };
        ContainerResponse response = await database.CreateContainerIfNotExistsAsync(
            properties,
            ThroughputProperties.CreateAutoscaleThroughput(autoscaleMaxThroughput),
            cancellationToken: cancellationToken);

        if (defaultTtl is not null && response.Resource.DefaultTimeToLive != defaultTtl)
        {
            response.Resource.DefaultTimeToLive = defaultTtl;
            await response.Container.ReplaceContainerAsync(response.Resource, cancellationToken: cancellationToken);
        }

        logger.LogInformation(
            "Container {Container} ready (pk={PartitionKeyPaths}, autoscale={Throughput}, ttl={Ttl})",
            name,
            string.Join(",", partitionKeyPaths),
            autoscaleMaxThroughput,
            defaultTtl);
    }

    private async Task TryEnsureVectorAsync(Database database, CancellationToken cancellationToken)
    {
        try
        {
            // Match the Java backend's existing emulator container. The target domain
            // model uses /sellerId, but changing a container PK requires recreation.
            var properties = new ContainerProperties(ContainerNames.ProductVectors, "/productId")
            {
                VectorEmbeddingPolicy = new VectorEmbeddingPolicy(
                    new Collection<Embedding>
                    {
                        new()
                        {
                            Path = "/embedding",
                            DataType = VectorDataType.Float32,
                            Dimensions = VectorDimensions,
                            DistanceFunction = DistanceFunction.Cosine
                        }
                    }),
                IndexingPolicy = new IndexingPolicy
                {
                    IncludedPaths = { new IncludedPath { Path = "/*" } },
                    ExcludedPaths = { new ExcludedPath { Path = "/embedding/*" } },
                    VectorIndexes =
                    {
                        new VectorIndexPath
                        {
                            Path = "/embedding",
                            Type = VectorIndexType.DiskANN
                        }
                    }
                }
            };

            ContainerResponse response = await database.CreateContainerIfNotExistsAsync(
                properties,
                ThroughputProperties.CreateAutoscaleThroughput(1000),
                cancellationToken: cancellationToken);
            ContainerResponse persisted = await response.Container.ReadContainerAsync(cancellationToken: cancellationToken);

            bool hasIndex = persisted.Resource.IndexingPolicy.VectorIndexes
                .Any(path => path.Path == "/embedding" && path.Type == VectorIndexType.DiskANN);
            Embedding? embedding = persisted.Resource.VectorEmbeddingPolicy?.Embeddings
                .FirstOrDefault(item => item.Path == "/embedding");
            bool embeddingCompatible = embedding is null
                || (embedding.Dimensions == VectorDimensions
                    && embedding.DataType == VectorDataType.Float32
                    && embedding.DistanceFunction == DistanceFunction.Cosine);

            if (hasIndex && embeddingCompatible)
            {
                vectorState.MarkReady();
                logger.LogInformation("Vector container is ready with a DiskANN index.");
            }
            else
            {
                logger.LogWarning(
                    "ProductVectors exists without a compatible persisted vector policy; vectorSearch remains false.");
            }
        }
        catch (Exception exception) when (
            exception is CosmosException or TimeoutException or ArgumentException)
        {
            logger.LogWarning(exception,
                "Vector container provisioning was not guaranteed; vectorSearch remains false.");
        }
    }
}
