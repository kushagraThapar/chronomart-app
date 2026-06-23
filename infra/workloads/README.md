# Workload Presets

JSON presets for `POST /api/v1/workloads/run`. Load the file contents as the request body.

## Runnable today

| File | Description |
|---|---|
| `hot-seller-mix.json` | 70% point-reads / 25% queries / 5% cart-upserts against Products + Cart. 16 users, 30s. |
| `cart-ttl-churn.json` | 80% cart-upserts / 20% point-reads against Cart — stresses TTL expiry path. 16 users, 30s. |
| `vector-throughput.json` | 100% `vectorSearch` against `ProductVectors` (seeded at startup); 5 deterministic query vectors, k=5. 8 users, 30s. |
| `bulk-ingest.json` | 100% `bulk` upsert against `Inventory` — 20 docs/batch via `BulkRunner`, single PK (`seller-001`). 4 users, 30s. |
| `hpk-hotspot.json` | 100% `hpkPointRead` against `Orders` (3-level HPK). **Pre-req:** drive the checkout flow first (`/checkout` from `/cart`) so the smoke order ids exist; otherwise every read is a 404 and the workload measures cache-miss + 404 latency only. 16 users, 30s. |
| `verify-register.json` | **Correctness oracle** — 50/50 `pointUpsert`/`pointRead` over an owned 500-key keyspace on `Inventory`, `verification.enabled=true`. The engine pre-seeds the keyspace with self-verifying values, then every read is L0-checked (wrong-id / checksum / field / not-found). Poll `GET /workloads/{runId}/anomalies`; a healthy run reports `anomalySummary.total=0`. 8 users, 20s. |
| `verify-checkout.json` | **Domain invariants (L2)** — 100% `checkout`: build a cart, place an `Orders` HPK order with a correct total, clear the cart, then read both back and assert `total == Σ line items`, `qty ≥ 1`, `price ≥ 0`, and cart-is-empty. Catches an SDK round-trip that corrupts the order or fails to clear the cart. 6 users, 15s. |

## Correctness verification (oracle)

A run with a `verification` block operates over a bounded, engine-owned keyspace so reads and
writes collide and every response can be checked. Each write embeds a self-verifying value
(`_verify` envelope + deterministic fields). Checks run in layers: **L0** intra-response
(`WRONG_ID`, `CHECKSUM_MISMATCH`, `FIELD_MISMATCH`, `PREDICATE_VIOLATION`, `VECTOR_ORDER_VIOLATION`);
**L1** temporal against a per-key reference model, graded by `level` (`STALE_READ`, `LOST_WRITE`,
`PHANTOM_READ`, `READ_YOUR_WRITE_VIOLATION`, `MONOTONIC_READ_VIOLATION`); **L2** domain invariants
on real `checkout` documents. Anomalies are summarised on the run status (`anomalySummary`) and
paged via `GET /workloads/{runId}/anomalies`.

### Offline analyzer + CI gate

Every verification run also records a structured single-register op history (`GET
/workloads/{runId}/history`). The **offline analyzer** re-derives settled writes from the op
intervals and checks real-time **linearizability** independently of the live oracle:

```
java -cp <backend.jar> -Dloader.main=com.chronomart.oracle.OfflineHistoryAnalyzer \
     org.springframework.boot.loader.launch.PropertiesLauncher history.json
# exits non-zero on any STALE_READ / LOST_WRITE / PHANTOM_READ
```

`scripts/oracle-gate.sh [BASE_URL] [PRESET]` runs a verification workload and **fails CI**
(non-zero exit) when ERROR anomalies exceed `THRESHOLD` (default 0); set `ANALYZER_JAR` to also
download the history and run the offline analyzer as a second, independent check.


## Op coverage

Every op named in `/api/v1/_meta/capabilities#features.workloads` is wired and executable:
`pointRead`, `pointUpsert`, `query`, `hpkPointRead`, `vectorSearch`, `bulk`, `cartUpsert`.

For `bulk`, the workload runner only supports `op: create|upsert` (replace/delete need
stable pre-existing ids; use `POST /bulk` directly for those). For `pointUpsert`, the
caller must specify the `pkField` (the field name on the synthesised doc that holds the
partition key value, e.g. `sellerId` for Products); the runner does not infer it from the
container name to keep the engine domain-agnostic.

For `vectorSearch`, the `ProductVectors` container is provisioned and seeded at startup
when the vNext emulator accepts the embedding policy; if seeding failed, the runner's
readiness flag flips and the validator rejects the workload at start with a clear error.
