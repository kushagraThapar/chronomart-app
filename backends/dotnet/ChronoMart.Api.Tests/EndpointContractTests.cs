using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;

namespace ChronoMart.Api.Tests;

public sealed class EndpointContractTests : IClassFixture<ChronoMartFactory>
{
    private readonly HttpClient client;

    public EndpointContractTests(ChronoMartFactory factory)
    {
        client = factory.CreateClient();
    }

    [Fact]
    public async Task Health_endpoint_is_pollable_and_has_standard_headers()
    {
        using HttpResponseMessage response = await client.GetAsync("/healthz");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Contains("x-ms-request-charge", response.Headers.Select(header => header.Key));
        Assert.Contains("x-ms-activity-id", response.Headers.Select(header => header.Key));
        Assert.Contains("x-cm-latency-ms", response.Headers.Select(header => header.Key));
        Assert.Contains("x-cm-sdk", response.Headers.Select(header => header.Key));
        Assert.Contains("x-cm-trace-id", response.Headers.Select(header => header.Key));
    }

    [Fact]
    public async Task Capabilities_endpoint_does_not_claim_vector_or_workloads()
    {
        using HttpResponseMessage response = await client.GetAsync("/api/v1/_meta/capabilities");
        using JsonDocument body = JsonDocument.Parse(await response.Content.ReadAsStringAsync());

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("dotnet", body.RootElement.GetProperty("sdk").GetString());
        Assert.False(body.RootElement.GetProperty("features").GetProperty("vectorSearch").GetBoolean());
        Assert.Equal(0, body.RootElement.GetProperty("features").GetProperty("workloads").GetArrayLength());
    }

    [Fact]
    public async Task Unsupported_vector_endpoint_returns_structured_501()
    {
        using HttpResponseMessage response = await client.PostAsJsonAsync(
            "/api/v1/vector/search",
            new { container = "ProductVectors", vector = new[] { 0.1f }, k = 1 });
        using JsonDocument body = JsonDocument.Parse(await response.Content.ReadAsStringAsync());

        Assert.Equal(HttpStatusCode.NotImplemented, response.StatusCode);
        Assert.Equal("NotImplemented",
            body.RootElement.GetProperty("error").GetProperty("code").GetString());
        Assert.Equal("dotnet",
            body.RootElement.GetProperty("error").GetProperty("sdk").GetString());
    }

    [Fact]
    public async Task Generic_query_rejects_non_allowlisted_container_without_cosmos()
    {
        using HttpResponseMessage response = await client.PostAsJsonAsync(
            "/api/v1/queries/run",
            new
            {
                container = "NotAChronoMartContainer",
                query = "SELECT * FROM c",
                enableCrossPartition = true
            });
        using JsonDocument body = JsonDocument.Parse(await response.Content.ReadAsStringAsync());

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Equal("BadRequest",
            body.RootElement.GetProperty("error").GetProperty("code").GetString());
    }

    [Fact]
    public async Task Workload_run_returns_structured_501_instead_of_404()
    {
        using HttpResponseMessage response = await client.PostAsJsonAsync(
            "/api/v1/workloads/cache_warmup/run",
            new { });

        Assert.Equal(HttpStatusCode.NotImplemented, response.StatusCode);
    }
}

public sealed class ChronoMartFactory : WebApplicationFactory<ChronoMart.Api.Program>
{
    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Testing");
        builder.ConfigureAppConfiguration((_, configuration) =>
        {
            configuration.AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["CHRONOMART_CONTAINER_INIT_ENABLED"] = "false",
                ["CHRONOMART_CONTAINER_INIT_VECTOR"] = "false",
                ["COSMOS_ENDPOINT"] = "https://localhost:8081/",
                ["COSMOS_ALLOW_INVALID_CERTS"] = "true"
            });
        });
    }
}
