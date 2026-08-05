using ChronoMart.Api;

namespace ChronoMart.Api.Tests;

public sealed class CapabilityTests
{
    [Fact]
    public void Manifest_reports_supported_and_unsupported_features_honestly()
    {
        var vector = new VectorCapabilityState();

        CapabilityManifest manifest = CapabilityProvider.Create(vector);

        Assert.Equal("dotnet", manifest.Sdk);
        Assert.StartsWith("Microsoft.Azure.Cosmos/3.62.0", manifest.SdkVersion);
        Assert.True((bool)manifest.Features["pointCrud"]);
        Assert.True((bool)manifest.Features["changeFeedPull"]);
        Assert.False((bool)manifest.Features["changeFeedProcessor"]);
        Assert.False((bool)manifest.Features["vectorSearch"]);
        Assert.Empty((string[])manifest.Features["workloads"]);
    }

    [Fact]
    public void Manifest_enables_vector_only_after_verified_provisioning()
    {
        var vector = new VectorCapabilityState();
        vector.MarkReady();

        CapabilityManifest manifest = CapabilityProvider.Create(vector);

        Assert.True((bool)manifest.Features["vectorSearch"]);
        Assert.NotEqual("none", manifest.VectorEmbeddingProvider);
    }
}
