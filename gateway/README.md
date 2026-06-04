# ChronoMart Gateway

Tiny Node.js + Fastify reverse proxy that fronts every per-language Cosmos DB SDK backend
in `/backends/`. The UI sends `X-Cosmos-SDK: java|dotnet|python|rust|go` on each request,
and this gateway forwards to the matching backend service.

It also:

- Serves a stub `/api/v1/_meta/capabilities` when a backend isn't configured yet, so
  the UI can still render Phase-0 / Phase-1 work-in-progress.
- Caches each backend's capability manifest for 60s.
- Tags every response with `x-cm-sdk` (which backend served it) and
  `x-cm-gateway-latency-ms` (proxy time).
- Emits Prometheus metrics at `/metrics` for ingestion by the OTel collector or
  Prometheus directly.

## Run locally (outside Docker)

```bash
npm install
npm run dev                # tsx watch mode
# in another shell:
curl -s http://localhost:8000/healthz
curl -sH 'X-Cosmos-SDK: java' http://localhost:8000/api/v1/_meta/capabilities | jq
```

## Config (env)

| Var                       | Default | Purpose                                     |
|---------------------------|---------|---------------------------------------------|
| `PORT`                    | `8000`  | Listen port                                 |
| `HOST`                    | `0.0.0.0`| Listen host                                |
| `DEFAULT_SDK`             | `java`  | Backend used when no header / query is given|
| `LOG_LEVEL`               | `info`  | Pino log level                              |
| `BACKEND_JAVA_URL`        | —       | e.g. `http://java-backend:8101`             |
| `BACKEND_DOTNET_URL`      | —       | e.g. `http://dotnet-backend:8102`           |
| `BACKEND_PYTHON_URL`      | —       | e.g. `http://python-backend:8103`           |
| `BACKEND_RUST_URL`        | —       | e.g. `http://rust-backend:8104`             |
| `BACKEND_GO_URL`          | —       | e.g. `http://go-backend:8105`               |

Empty / unset backend URLs mean the gateway returns a stub capability manifest and
a structured `503 BackendNotConfigured` for resource calls.
