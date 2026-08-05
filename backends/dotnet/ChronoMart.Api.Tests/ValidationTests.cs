using System.Text.Json;
using ChronoMart.Api;

namespace ChronoMart.Api.Tests;

public sealed class ValidationTests
{
    [Theory]
    [InlineData("Products")]
    [InlineData("ProductsHpk")]
    [InlineData("Orders")]
    [InlineData("Cart")]
    public void Canonical_containers_are_allowed(string container)
    {
        ContractValidation.AllowedContainer(container);
    }

    [Fact]
    public void Unknown_container_is_rejected()
    {
        ApiException exception = Assert.Throws<ApiException>(
            () => ContractValidation.AllowedContainer("ArbitraryContainer"));

        Assert.Equal(400, exception.StatusCode);
        Assert.Contains("allow-list", exception.Message);
    }

    [Fact]
    public void Hierarchical_partition_key_is_supported()
    {
        using JsonDocument json = JsonDocument.Parse("""["seller-1","dive","prod-1"]""");

        var key = PartitionKeys.Parse(json.RootElement, required: true);

        Assert.NotNull(key);
        Assert.Contains("seller-1", key.Value.ToString());
    }

    [Fact]
    public void Unsafe_integer_partition_key_is_rejected()
    {
        using JsonDocument json = JsonDocument.Parse("9007199254740993");

        ApiException exception = Assert.Throws<ApiException>(
            () => PartitionKeys.Parse(json.RootElement, required: true));

        Assert.Contains("string or an array", exception.Message);
    }

    [Fact]
    public void Unsafe_integer_hpk_level_is_rejected()
    {
        using JsonDocument json = JsonDocument.Parse("""["seller",9007199254740993]""");

        ApiException exception = Assert.Throws<ApiException>(
            () => PartitionKeys.Parse(json.RootElement, required: true));

        Assert.Contains("safe double range", exception.Message);
    }

    [Fact]
    public void Oversized_double_hpk_level_beyond_int64_is_rejected()
    {
        using JsonDocument json = JsonDocument.Parse("""["seller",100000000000000000000]""");

        ApiException exception = Assert.Throws<ApiException>(
            () => PartitionKeys.Parse(json.RootElement, required: true));

        Assert.Contains("safe double range", exception.Message);
    }

    [Fact]
    public void Hpk_schema_matches_java_and_vnext_emulator()
    {
        Assert.Equal(["/sellerId", "/categoryId"], PartitionKeys.ProductHpkPaths);
        Assert.Equal(["/customerId", "/yearMonth"], PartitionKeys.OrderHpkPaths);
    }

    [Fact]
    public void Hpk_point_keys_use_two_level_tuples()
    {
        string productKey = PartitionKeys.ForProductHpk("seller-1", "dive").ToString();
        string orderKey = PartitionKeys.ForOrder("customer-1", "2026-08").ToString();

        Assert.Contains("seller-1", productKey);
        Assert.Contains("dive", productKey);
        Assert.Contains("customer-1", orderKey);
        Assert.Contains("2026-08", orderKey);
    }

    [Theory]
    [InlineData(null, null, 100)]
    [InlineData(25, null, 25)]
    [InlineData(null, 50, 50)]
    [InlineData(25, 50, 25)]
    public void Seller_limit_supports_java_parameter_and_page_size_alias(
        int? limit,
        int? pageSize,
        int expected)
    {
        Assert.Equal(expected, Program.ResolveSellerLimit(limit, pageSize));
    }

    [Theory]
    [InlineData(0)]
    [InlineData(1001)]
    public void Seller_limit_is_bounded(int limit)
    {
        ApiException exception = Assert.Throws<ApiException>(
            () => Program.ResolveSellerLimit(limit, null));

        Assert.Equal(400, exception.StatusCode);
        Assert.Contains("limit", exception.Message);
    }
}
