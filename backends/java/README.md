# ChronoMart — Java Backend

Spring Boot 4.0 + `azure-cosmos` 4.80.0, talking to the Cosmos DB vNext emulator (or any
real account in later phases).

This is one of N language backends implementing the same OpenAPI contract in
`contracts/openapi.yaml`. The gateway routes requests to it based on the
`X-Cosmos-SDK: java` header.

## Scope: Phase 1 / PR2

What's wired up:

| Feature                                   | Status                       |
|-------------------------------------------|------------------------------|
| `GET /healthz`                            | ✅ live                      |
| `GET /api/v1/_meta/capabilities`          | ✅ honest manifest           |
| `GET /api/v1/sellers`                     | ✅ Cosmos-backed (Sellers)   |
| `GET /api/v1/sellers/{id}`                | ✅ point read with 404 path  |
| Container provisioning on boot            | ✅ idempotent, repairs TTL   |
| Full CRUD on every resource               | 🚧 PR3                       |
| `POST /queries/run`, `/bulk`, `/batch`    | 🚧 PR3-PR4                   |
| Change feed (pull mode)                   | 🚧 PR5                       |
| Vector search                             | 🚧 PR6                       |
| OTLP traces + diagnostics ring buffer     | 🚧 PR7                       |

## Local quick start

```bash
# from chronomart-app/
docker compose -f infra/docker/docker-compose.yml up -d cosmos-emulator
docker compose -f infra/docker/docker-compose.yml up -d --build java-backend

curl http://localhost:8101/healthz
curl -s http://localhost:8101/api/v1/sellers | jq
curl -s http://localhost:8101/api/v1/_meta/capabilities | jq

# Through the gateway (SDK switcher):
curl -s -H 'X-Cosmos-SDK: java' http://localhost:8000/api/v1/sellers | jq
```

Build outside Docker:

```bash
mvn -B -DskipTests package
```

## Configuration

All settings are environment variables (Spring's `@ConfigurationProperties` binds them
case-insensitively to the `chronomart.cosmos.*` prefix).

| Env var                                | Default                             | Notes |
|----------------------------------------|-------------------------------------|-------|
| `COSMOS_ENDPOINT`                      | `https://localhost:8081/`           |       |
| `COSMOS_KEY`                           | well-known emulator key             | Required when `auth-mode=key` |
| `COSMOS_DATABASE`                      | `ChronoMart`                        |       |
| `COSMOS_AUTH_MODE`                     | `key`                               | Only `key` supported in Phase 1 |
| `COSMOS_ALLOW_INVALID_CERTS`           | `false`                             | Set `true` against the emulator |
| `COSMOS_EMULATOR_HOST`                 | _empty_                             | Set to `cosmos-emulator` inside Compose so the SDK's `isEmulatorHost` matches |
| `CHRONOMART_INIT_ENABLED`              | `true`                              | Provisions containers + repairs Cart TTL on boot |
| `CHRONOMART_INIT_VECTOR`               | `false`                             | Off by default until vNext emulator DiskANN support is verified in PR6 |

> The two env vars `COSMOS_ALLOW_INVALID_CERTS` + `COSMOS_EMULATOR_HOST` must be set
> **together** when running inside Docker against the emulator on a non-`localhost`
> hostname. The Java SDK gates its self-signed-cert bypass behind a hostname check;
> without `COSMOS_EMULATOR_HOST` the SDK's `isEmulatorHost` only matches
> `localhost / 127.0.0.1 / ::1` and you'll get TLS handshake errors.

## Why the Container Initializer matters

The cosmoshell seed scripts (`scripts/seed/*.csh`) provision schema, but they can't:

- Set `defaultTimeToLive` on `Cart` (so per-item `ttl` is silently ignored).
- Express vector embedding policy or DiskANN indexes (`ProductVectors`).

The Java backend's `ContainerInitializer` runs once on boot, reads each container's
current properties, and replaces them when they drift from what `contracts/domain.md`
specifies. This is also what makes the same image work against a fresh (un-seeded)
emulator.

## Observability

- Prometheus metrics: `http://localhost:8101/actuator/prometheus`
- Spring Boot health: `http://localhost:8101/actuator/health`
- OTLP traces: wired in PR7.
