# Workload Presets

JSON presets for `POST /api/v1/workloads/run`. Load the file contents as the request body.

## Runnable today (v1)

| File | Description |
|---|---|
| `hot-seller-mix.json` | 70% point-reads / 25% queries / 5% cart-upserts against Products + Cart. 16 users, 30s. |
| `cart-ttl-churn.json` | 80% cart-upserts / 20% point-reads against Cart — stresses TTL expiry path. 16 users, 30s. |

## Pre-staged for PR2 (will return 400 today)

These presets use ops that are not yet implemented (`pointUpsert`, `hpkPointRead`,
`vectorSearch`, `bulk`). They are checked in now so the UI dropdown can display them
today without a code change when PR2 lands.

| File | Blocked on |
|---|---|
| `hpk-hotspot.json` | `hpkPointRead` |
| `vector-throughput.json` | `vectorSearch` |
| `bulk-ingest.json` | `bulk` |
