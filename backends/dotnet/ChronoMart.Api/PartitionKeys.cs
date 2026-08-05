using System.Text.Json;
using Microsoft.Azure.Cosmos;

namespace ChronoMart.Api;

public static class PartitionKeys
{
    public static IReadOnlyList<string> ProductHpkPaths { get; } =
        Array.AsReadOnly(["/sellerId", "/categoryId"]);

    public static IReadOnlyList<string> OrderHpkPaths { get; } =
        Array.AsReadOnly(["/customerId", "/yearMonth"]);

    // The contract target includes /id as a third leaf. The vNext emulator currently
    // rejects three-level HPK creation from the Java/.NET SDKs, while the existing
    // Compose containers intentionally use these two-level tuples. The document id
    // remains the separate id argument on point reads.
    public static PartitionKey ForProductHpk(string sellerId, string categoryId) =>
        new PartitionKeyBuilder().Add(sellerId).Add(categoryId).Build();

    public static PartitionKey ForOrder(string customerId, string yearMonth) =>
        new PartitionKeyBuilder().Add(customerId).Add(yearMonth).Build();

    public static PartitionKey? Parse(JsonElement? raw, bool required)
    {
        if (raw is null || raw.Value.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined)
        {
            if (required)
            {
                throw ContractValidation.BadRequest("partitionKey is required for this operation.");
            }
            return null;
        }

        JsonElement value = raw.Value;
        if (value.ValueKind == JsonValueKind.String)
        {
            string text = value.GetString() ?? "";
            if (string.IsNullOrWhiteSpace(text))
            {
                throw ContractValidation.BadRequest("partitionKey must not be blank.");
            }
            return new PartitionKey(text);
        }

        if (value.ValueKind != JsonValueKind.Array)
        {
            throw ContractValidation.BadRequest("partitionKey must be a string or an array of primitives.");
        }

        if (value.GetArrayLength() == 0)
        {
            if (required)
            {
                throw ContractValidation.BadRequest("partitionKey must not be an empty array.");
            }
            return null;
        }

        var builder = new PartitionKeyBuilder();
        foreach (JsonElement level in value.EnumerateArray())
        {
            switch (level.ValueKind)
            {
                case JsonValueKind.String:
                    builder.Add(level.GetString()!);
                    break;
                case JsonValueKind.True:
                case JsonValueKind.False:
                    builder.Add(level.GetBoolean());
                    break;
                case JsonValueKind.Number:
                    if (level.TryGetInt64(out long integer)
                        && (integer == long.MinValue || Math.Abs(integer) > (1L << 53)))
                    {
                        throw ContractValidation.BadRequest(
                            $"partitionKey numeric level {integer} exceeds the safe double range (±2^53); send it as a string.");
                    }
                    builder.Add(level.GetDouble());
                    break;
                default:
                    throw ContractValidation.BadRequest(
                        "partitionKey array levels must be strings, numbers, or booleans.");
            }
        }
        return builder.Build();
    }
}
