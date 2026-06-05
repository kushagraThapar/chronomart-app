# ChronoMart — Backend / SDK Testing Harness

ChronoMart is a multi-seller watch marketplace that exists for one reason: **to be a
realistic load harness for the Azure Cosmos DB SDKs** (Java, .NET, Python, Rust, Go).

The same OpenAPI surface is implemented by every per-language backend in `/backends/`. A
lightweight gateway in `/gateway/` routes requests to the chosen backend based on an
`X-Cosmos-SDK` header from the [ChronoMart UI](https://github.com/kushagraThapar/chronomart-ui).
We can flip the SDK that's serving the same user click and compare RU charges, latencies,
diagnostics, and retry behavior side-by-side.

This repo contains everything except the UI:

```
chronomart-app/
├── contracts/          OpenAPI v1, domain model, capability manifest, contract tests
├── gateway/            Node.js + Fastify router (header-based SDK dispatch)
├── backends/           Per-language Cosmos SDK implementations of the contract
│   ├── java/   .NET/  python/  rust/  go/
├── workloads/          Composable scenario definitions (YAML)
├── infra/
│   ├── docker/         docker-compose for local dev (emulator + everything)
│   ├── aks/            Helm + Bicep for the AKS path against real Cosmos accounts
│   └── observability/  OTel collector, Prometheus, Grafana (provisioning + dashboards)
├── scripts/            Cosmoshell .csh seed scripts, dev helpers
└── .github/workflows/  Per-backend smoke CI + cross-SDK contract matrix
```

## Quick start

```bash
# 1. Pull the Cosmos DB vNext emulator image
docker pull mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:vnext-latest

# 2. Bring everything up (emulator + observability stack + gateway + backends)
docker compose -f infra/docker/docker-compose.yml up -d

# 3. Verify the gateway is healthy
curl -s http://localhost:8000/healthz

# 4. Hit the API via the gateway
curl -s -H 'X-Cosmos-SDK: java' http://localhost:8000/api/v1/_meta/capabilities | jq

# 5. (Optional) Open dashboards
open http://localhost:3000     # Grafana (admin / admin)
open http://localhost:1234     # Cosmos DB Data Explorer (emulator UI)
```

## Switching the SDK behind the UI

Set the `X-Cosmos-SDK` header on every request (the UI's SDK switcher does this
automatically). Valid values: `java`, `dotnet`, `python`, `rust`, `go`. If a feature isn't
supported by the chosen SDK, the capability manifest returned from
`/api/v1/_meta/capabilities` reports it as `false` and the UI greys-out that action.

## Phase status

| Phase | Status | Notes |
|------:|:------:|:------|
| 0     | ✅     | Gateway, OpenAPI v1, seed data, docker-compose, observability stack |
| 1     | 🚧     | Java backend bootstrap (PR2 ready), CRUD/queries/bulk/changeFeed/vector in subsequent PRs |
| 2-6   | ⏳     | .NET, Python, workloads framework, Rust+Go, AKS migration |

The Java backend currently exposes `/healthz`, `/api/v1/_meta/capabilities`,
`/api/v1/sellers`, `/api/v1/sellers/{id}` and idempotently provisions all containers
(including repairing Cart's default TTL that cosmoshell seed scripts can't set).

## Authoritative design doc

The full architecture, repository layout rationale, phasing, AKS migration path, and risks
live in the session plan at:

`~/.copilot/session-state/<session-id>/plan.md`

A summary will be promoted to `/docs/design.md` once Phase 0 lands.

## Ports

| Port | Service                          |
|------|----------------------------------|
| 8000 | Gateway (HTTP)                   |
| 8081 | Cosmos emulator (TLS data plane) |
| 8080 | Cosmos emulator (health probes)  |
| 1234 | Cosmos emulator (Data Explorer)  |
| 3000 | Grafana                          |
| 9090 | Prometheus                       |
| 4317 | OTel collector (OTLP gRPC)       |
| 4318 | OTel collector (OTLP HTTP)       |
| 8101 | Java backend                     |
| 8102 | .NET backend                     |
| 8103 | Python backend                   |
| 8104 | Rust backend                     |
| 8105 | Go backend                       |

## License

MIT
