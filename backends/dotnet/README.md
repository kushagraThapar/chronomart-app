# ChronoMart .NET backend

ASP.NET Core .NET 9 minimal API using `Microsoft.Azure.Cosmos` **3.62.0**. It serves the
shared ChronoMart contract on port `8102`.

## Run

```bash
dotnet restore ChronoMart.sln
dotnet run --project ChronoMart.Api
curl http://localhost:8102/healthz
curl http://localhost:8102/api/v1/_meta/capabilities
```

## Configuration

| Environment variable | Default |
|---|---|
| `ASPNETCORE_URLS` | `http://0.0.0.0:8102` |
| `COSMOS_ENDPOINT` | `https://localhost:8081/` |
| `COSMOS_KEY` | well-known emulator key |
| `COSMOS_DATABASE` | `ChronoMart` |
| `COSMOS_ALLOW_INVALID_CERTS` | `false` |
| `COSMOS_EMULATOR_HOST` | empty |
| `CHRONOMART_CONTAINER_INIT_ENABLED` | `true` |
| `CHRONOMART_CONTAINER_INIT_VECTOR` | `true` |

Set `COSMOS_ALLOW_INVALID_CERTS=true` only for localhost or the exact host named by
`COSMOS_EMULATOR_HOST`. The client uses Gateway mode for emulator endpoints and refuses
certificate bypass for other hosts.

## Implemented

- Health, capabilities, diagnostics, feed ranges, and derived cache snapshots.
- Seller/product/customer/order/review/cart/inventory operations in the OpenAPI contract.
- `GET /api/v1/sellers` follows the Java/UI contract: `limit=1..1000` (default `100`)
  and a JSON array response. `pageSize` is accepted as a compatibility alias.
- vNext-emulator HPK provisioning matching Java/Compose:
  `ProductsHpk` uses `[/sellerId, /categoryId]` and `Orders` uses
  `[/customerId, /yearMonth]`.
- Cart container TTL repair (`604800` seconds).
- Parameterized generic query, bounded bulk, transactional batch, patch, pull change feed,
  and capability-gated vector search.
- Standard response headers and structured errors.

The vector capability becomes `true` only after startup creation/read-back verifies a DiskANN
index on `/embedding`. Provisioning failures leave the feature off and `/vector/search` returns
structured `501`.

The domain contract targets a third `/id` HPK leaf for `ProductsHpk` and `Orders`, but it is
intentionally deferred. The vNext emulator currently fails three-level HPK creation through
the Java/.NET SDK path, while the same two-level containers are pre-created by Compose.
Point reads therefore route with the two-level tuple and pass the document `id` separately.
Because partition-key definitions are immutable, adopting the target three-level shape later
requires dropping and recreating those containers after emulator support is verified.

Workload/oracle execution is intentionally unsupported in this parity slice: the manifest
reports an empty workload list and workload detail/run endpoints return structured `501`.
Change Feed Processor is also false; pull mode is implemented.

## Design notes

- Point operations always include the complete logical partition key, and caller-selected
  containers are allowlisted. HPK tuple order follows `contracts/domain.md`. This aligns with
  `cosmosdb-design-docs/04-partitioning.md` (PK/EPK routing and opaque feed ranges).
- Pull change feed returns and accepts opaque continuations instead of persisting physical
  partition range IDs, following `12-change-feed.md`.
- One long-lived `CosmosClient` preserves its session-token cache and Session consistency
  guarantees (`16-session-tokens.md`).
- SDK retry behavior is left to the Cosmos client; application code does not retry non-idempotent
  writes broadly. See `17-status-codes-and-sdk-retries.md`.

## Tests

Tests disable provisioning and therefore require no live Cosmos endpoint:

```bash
dotnet test ChronoMart.sln
```

NuGet dependency graphs are committed in each project's `packages.lock.json`. CI can verify
reproducibility with:

```bash
dotnet restore ChronoMart.sln --locked-mode
```
