# ChronoMart Domain Model

ChronoMart is a multi-seller watch marketplace. The domain is small enough to read end-to-end
but rich enough to exercise every Cosmos DB feature in scope for Phase 1.

## Database

Single database: **`ChronoMart`**.

## Containers

| Container         | Partition Key                  | Throughput  | TTL    | Notes                                       |
|-------------------|--------------------------------|-------------|--------|---------------------------------------------|
| `Sellers`         | `/id`                          | autoscale 1k| —      | Small set; one doc per seller               |
| `Products`        | `/sellerId`                    | autoscale 4k| —      | Hot read by seller                          |
| `ProductsHpk`     | `/sellerId, /categoryId`       | autoscale 4k| —      | Emulator-compatible HPK; `/id` remains the document id |
| `Inventory`       | `/sellerId`                    | autoscale 1k| —      | Co-located with `Products`                  |
| `ProductVectors`  | `/productId`                   | autoscale 1k| —      | DiskANN vector index on `/embedding`; emulator-compatible layout |
| `Customers`       | `/id`                          | autoscale 1k| —      | One doc per customer                        |
| `Orders`          | `/customerId, /yearMonth`      | autoscale 4k| —      | Emulator-compatible HPK, time-bucketed      |
| `Reviews`         | `/productId`                   | autoscale 1k| —      | Reviews co-located by product               |
| `Cart`            | `/customerId`                  | autoscale 1k| 604800 | TTL = 7 days; abandoned carts auto-purge    |
| `ChangeFeedLease` | `/id`                          | autoscale 1k| —      | Lease container for Java/.NET change feed   |

Backends MUST create databases and containers (with the partition keys, TTL, and indexing
policy below) on startup if they do not exist. This makes the harness reproducible — drop the
emulator volume and start over.

## Entity shapes (JSON Schema fragments)

### Seller

```json
{
  "id": "seller-001",
  "name": "Quartz & Co.",
  "country": "CH",
  "rating": 4.7,
  "joinedAt": "2024-01-15T00:00:00Z"
}
```

### Product

```json
{
  "id": "prod-9f3c…",
  "sellerId": "seller-001",
  "categoryId": "dive",                   // ProductsHpk uses this as 2nd PK level (leaf is /id)
  "name": "Quartz Diver 200",
  "brand": "Quartz & Co.",
  "model": "QD-200",
  "priceUsd": 449.0,
  "currency": "USD",
  "attributes": {
    "movement": "automatic",
    "diameterMm": 42,
    "waterResistanceM": 200,
    "caseMaterial": "316L"
  },
  "tags": ["dive", "automatic", "swiss"],
  "images": ["https://…/qd200-a.jpg"],
  "createdAt": "2026-03-01T12:00:00Z",
  "updatedAt": "2026-05-30T09:14:00Z"
}
```

### ProductVector

```json
{
  "id": "prod-9f3c…",
  "sellerId": "seller-001",
  "name": "Quartz Diver 200",
  "embedding": [0.12, -0.03, …],         // length = 1024 (mxbai-embed-large default)
  "embeddingModel": "mxbai-embed-large"
}
```

Container vector embedding policy (set at create time):

```json
{
  "vectorEmbeddings": [
    { "path": "/embedding",
      "dataType": "float32",
      "distanceFunction": "cosine",
      "dimensions": 1024 }
  ]
}
```

### Customer

```json
{
  "id": "cust-7a2…",
  "name": "Alice Liddell",
  "email": "alice@example.com",
  "country": "US",
  "tier": "gold",
  "createdAt": "2025-09-10T00:00:00Z"
}
```

### Order

```json
{
  "id": "ord-…",
  "customerId": "cust-7a2…",
  "yearMonth": "2026-06",                 // 2nd hierarchical PK level (leaf is /id)
  "status": "paid",                       // pending | paid | shipped | delivered | cancelled
  "items": [
    { "productId": "prod-…", "sellerId": "seller-001", "qty": 1, "unitPriceUsd": 449.0 }
  ],
  "totalUsd": 449.0,
  "createdAt": "2026-06-02T11:31:00Z",
  "shippedAt": null
}
```

### Review

```json
{
  "id": "rev-…",
  "productId": "prod-9f3c…",
  "customerId": "cust-7a2…",
  "rating": 5,
  "title": "Solid daily diver",
  "body": "…",
  "createdAt": "2026-05-15T18:02:00Z"
}
```

### Cart

```json
{
  "id": "cart-cust-7a2…",                 // 1:1 with customerId
  "customerId": "cust-7a2…",
  "items": [
    { "productId": "prod-…", "qty": 1, "addedAt": "2026-06-01T09:00:00Z" }
  ],
  "updatedAt": "2026-06-03T14:22:00Z",
  "ttl": 604800
}
```

### Inventory

```json
{
  "id": "inv-prod-9f3c…",
  "sellerId": "seller-001",
  "productId": "prod-9f3c…",
  "available": 12,
  "reserved": 1,
  "updatedAt": "2026-06-02T11:31:00Z"
}
```

## Indexing policy notes

- All containers use the default indexing policy unless noted.
- `ProductVectors` excludes `/embedding/*` from the JSON index path and registers it under
  the `vectorIndexes` policy (DiskANN).
- `Products.tags` is included by default and benefits from `ARRAY_CONTAINS` queries.
- For `Orders` we'll keep default indexing in Phase 1; later workloads may experiment with
  composite indexes for `customerId + createdAt DESC`.

## Partition key choices — why

- **`/sellerId` on Products / Inventory** co-locates a seller's catalog and stock — typical
  "merchant dashboard" reads stay single-partition. `ProductVectors` currently uses
  `/productId` to match the container shape accepted by the vNext emulator and shared by the
  Java and .NET backends; changing it to `/sellerId` requires recreating the container.
- **`/customerId` on Orders / Cart / Reviews-by-customer-id queries** is the obvious access
  path for a logged-in customer; reviews are partitioned by `/productId` instead because the
  most common access pattern is "show reviews for this product".
- **Hierarchical `/customerId, /yearMonth` on Orders** showcases time-bucketed hierarchical
  PKs and lets us test partial-PK queries ("all of this customer's orders this month" vs
  "all of this customer's orders ever").
- **Hierarchical `/sellerId, /categoryId` on ProductsHpk** mirrors a catalog browse pattern;
  prefix scans on `/sellerId` and full-tuple point reads exercise hierarchical routing.
  It's a separate container from `Products` so we can run the same query against both
  layouts and compare RU/latency.

The target real-account layout adds `/id` as a third HPK level for both containers. The
vNext emulator currently creates the two-level shape reliably across cosmoshell, Java, and
.NET, while three-level SDK creation is inconsistent. Because partition-key definitions are
immutable, moving to the target layout requires dropping and recreating these containers.
