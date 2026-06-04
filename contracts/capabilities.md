# ChronoMart Capability Manifest

Every backend exposes `GET /api/v1/_meta/capabilities`. The UI fetches it whenever the user
flips the SDK switcher, caches the result, and uses it to grey-out unsupported actions.
Backends MUST report capabilities honestly — claiming `true` and then 501-ing is a bug.

## Manifest shape

```json
{
  "sdk": "java",
  "sdkVersion": "azure-cosmos 4.69.0",
  "apiVersions": ["v1"],
  "features": {
    "pointCrud":            true,
    "queries":              true,
    "queriesCrossPartition":true,
    "bulk":                 true,
    "transactionalBatch":   true,
    "changeFeedPull":       true,
    "changeFeedProcessor":  true,
    "hierarchicalPk":       true,
    "ttl":                  true,
    "patch":                true,
    "vectorSearch":         true,
    "fullTextSearch":       false,
    "feedRanges":           true,
    "diagnostics":          "full",     // "none" | "minimal" | "full"
    "cacheInspection":      true,
    "workloads":            ["cache_warmup","feed_range_scan","txn_batch_conflict",
                             "hpk_partition_targeting","session_token_consistency",
                             "changefeed_continuation_replay"]
  },
  "limits": {
    "maxBulkItems":         100,
    "maxBatchItems":         50,
    "maxQueryPageSize":     100
  }
}
```

## Feature flag catalogue (Phase 1)

| Flag                      | Description                                                                              |
|---------------------------|------------------------------------------------------------------------------------------|
| `pointCrud`               | Create / Read / Replace / Upsert / Delete single document by `(id, pk)`                  |
| `queries`                 | Parameterized SQL queries on a single partition                                          |
| `queriesCrossPartition`   | Cross-partition queries with continuation tokens                                         |
| `bulk`                    | Bulk write API (`bulk` / `executeBulkOperations` / equivalent) returning per-item status |
| `transactionalBatch`      | Transactional batch within a single partition key                                        |
| `changeFeedPull`          | Pull-mode change feed iteration                                                          |
| `changeFeedProcessor`     | Push-mode change feed processor with a lease container                                   |
| `hierarchicalPk`          | Multi-level partition keys (containers `ProductsHpk`, `Orders`)                          |
| `ttl`                     | Container-level + per-item TTL                                                           |
| `patch`                   | Partial document update                                                                  |
| `vectorSearch`            | `VectorDistance()` queries on a vector-indexed container                                 |
| `fullTextSearch`          | Full-text search (`FullTextContains`, etc.)                                              |
| `feedRanges`              | `getFeedRanges()` + query targeted at a feed range                                       |
| `diagnostics`             | Level of diagnostics returned via `/_meta/diagnostics` (`none`/`minimal`/`full`)         |
| `cacheInspection`         | Backend can report PK-range / container cache state via `/_meta/caches`                  |
| `workloads`               | List of composable workload ids the backend's workload runner can execute                |

## Known gaps (Phase 1 / 5)

- **Rust** (`azure_data_cosmos 0.34.0`) does not expose change feed publicly — will report
  `changeFeedPull: false`, `changeFeedProcessor: false`. Other gaps tracked once we implement.
- **Go** SDK gaps (bulk, change feed processor) — confirmed when we implement Phase 5.
- **Python** SDK has its own change feed pull APIs; processor is sync-only. Reported per-SDK.

## Rules

1. Capability manifest is **authoritative**. If a flag is `false`, the corresponding endpoint
   MUST return `501 Not Implemented` with the standard error model — never 500 or 404.
2. `sdkVersion` MUST be the actual loaded SDK version at runtime, not a build-time constant.
3. The gateway caches the merged manifest per backend for 60s; backends should not change
   capabilities at runtime.
