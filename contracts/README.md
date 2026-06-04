# ChronoMart Contracts

This folder is the **single source of truth** for the ChronoMart REST contract that every
language backend in `/backends/` implements. The UI, gateway, and contract tests all consume
the same `openapi.yaml`.

| File             | Purpose                                                                 |
|------------------|-------------------------------------------------------------------------|
| `openapi.yaml`   | OpenAPI 3.1 contract for the public API (`/api/v1/*`)                   |
| `domain.md`      | Canonical entity model — containers, partition keys, indexing, TTL      |
| `capabilities.md`| Feature flag catalogue (what `/_meta/capabilities` may report)          |
| `tests/`         | Language-agnostic contract test suite (HTTP + JSON Schema) per backend  |

## How a backend complies

1. Read `openapi.yaml`.
2. Implement every operation it can (omit by returning `501 Not Implemented` + structured
   error, **and** by reporting `false` in the capability manifest).
3. Always emit the standard response headers below.
4. Boot, create databases/containers per `domain.md` if not present, optionally apply seed
   scripts mounted at `/init`, then start serving.

## Standard response headers (every operation)

| Header                | Meaning                                                          |
|-----------------------|------------------------------------------------------------------|
| `x-ms-request-charge` | RU charge for the underlying Cosmos request(s) — float as string |
| `x-ms-activity-id`    | Cosmos activity id (or per-request correlation id)               |
| `x-cm-latency-ms`     | Total backend-side latency for the operation                     |
| `x-cm-sdk`            | SDK identifier (`java` / `dotnet` / …) and version               |
| `x-cm-trace-id`       | W3C trace id (`traceparent` propagated from the gateway)         |

## Standard error model

```json
{
  "error": {
    "code": "string",                  // e.g. "Conflict", "NotImplemented"
    "message": "string",               // human-readable
    "sdk": "java",
    "sdkVersion": "4.69.0",
    "cosmosStatusCode": 409,           // optional, when wrapping a Cosmos error
    "cosmosSubStatusCode": 0,          // optional
    "activityId": "…",                 // optional
    "traceId": "…"                     // optional W3C trace id
  }
}
```

## Versioning

`/api/v1/` is the current major. Breaking changes bump to `/v2/`. Capability manifest
declares the API versions a backend supports.
