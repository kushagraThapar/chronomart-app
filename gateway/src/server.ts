import Fastify, { FastifyInstance, FastifyPluginAsync, FastifyReply, FastifyRequest } from "fastify";
import metricsPluginRaw from "fastify-metrics";
import { request as undiciRequest } from "undici";

// fastify-metrics ships as CJS with a `default` export when loaded under ESM.
const metricsPlugin = (metricsPluginRaw as unknown as { default: FastifyPluginAsync }).default
  ?? (metricsPluginRaw as unknown as FastifyPluginAsync);

type Sdk = "java" | "dotnet" | "python" | "rust" | "go";

const VALID_SDKS: Sdk[] = ["java", "dotnet", "python", "rust", "go"];

interface BackendConfig {
  sdk: Sdk;
  url: string;
}

interface CapabilityCacheEntry {
  manifest: unknown;
  fetchedAt: number;
}

const CAPABILITY_TTL_MS = 60_000;

function loadBackends(): Record<Sdk, BackendConfig> {
  const result = {} as Record<Sdk, BackendConfig>;
  for (const sdk of VALID_SDKS) {
    const url = process.env[`BACKEND_${sdk.toUpperCase()}_URL`]?.trim() ?? "";
    result[sdk] = { sdk, url };
  }
  return result;
}

function pickSdk(req: FastifyRequest, defaultSdk: Sdk): Sdk {
  const headerSdk = (req.headers["x-cosmos-sdk"] as string | undefined)?.toLowerCase();
  const querySdk = (req.query as Record<string, string> | undefined)?.sdk?.toLowerCase();
  const candidate = headerSdk ?? querySdk ?? defaultSdk;
  return VALID_SDKS.includes(candidate as Sdk) ? (candidate as Sdk) : defaultSdk;
}

function stubManifest(sdk: Sdk, reason: string) {
  return {
    sdk,
    sdkVersion: "stub-0.0.0",
    apiVersions: ["v1"],
    features: {
      pointCrud: false, queries: false, queriesCrossPartition: false,
      bulk: false, transactionalBatch: false,
      changeFeedPull: false, changeFeedProcessor: false,
      hierarchicalPk: false, ttl: false, patch: false,
      vectorSearch: false, fullTextSearch: false,
      feedRanges: false, diagnostics: "none",
      cacheInspection: false, workloads: []
    },
    limits: { maxBulkItems: 0, maxBatchItems: 0, maxQueryPageSize: 0 },
    stub: true,
    reason
  };
}

export async function build(): Promise<FastifyInstance> {
  const defaultSdk = ((process.env.DEFAULT_SDK ?? "java").toLowerCase() as Sdk);
  const backends = loadBackends();
  const capabilityCache = new Map<Sdk, CapabilityCacheEntry>();

  const app = Fastify({
    logger: {
      level: process.env.LOG_LEVEL ?? "info",
      transport: process.env.NODE_ENV === "production" ? undefined : {
        target: "pino-pretty",
        options: { colorize: true, translateTime: "SYS:HH:MM:ss.l" }
      }
    },
    disableRequestLogging: false,
    genReqId: (req) => (req.headers["x-request-id"] as string | undefined) ?? crypto.randomUUID()
  });

  await app.register(metricsPlugin as never, {
    endpoint: "/metrics",
    routeMetrics: { enabled: true }
  } as never);

  app.get("/healthz", async () => ({ ok: true, service: "gateway" }));

  app.get("/api/v1/_meta/capabilities", async (req, reply) => {
    const sdk = pickSdk(req, defaultSdk);
    const backend = backends[sdk];
    if (!backend.url) {
      reply.header("x-cm-sdk", sdk);
      return stubManifest(sdk, `No backend configured for SDK '${sdk}' (BACKEND_${sdk.toUpperCase()}_URL is empty).`);
    }

    const now = Date.now();
    const cached = capabilityCache.get(sdk);
    if (cached && now - cached.fetchedAt < CAPABILITY_TTL_MS) {
      reply.header("x-cm-sdk", sdk);
      reply.header("x-cm-cache", "hit");
      return cached.manifest;
    }

    try {
      const res = await undiciRequest(`${backend.url}/api/v1/_meta/capabilities`, {
        headersTimeout: 5_000,
        bodyTimeout: 5_000,
        headers: { "x-request-id": req.id }
      });
      const body = await res.body.json();
      capabilityCache.set(sdk, { manifest: body, fetchedAt: now });
      reply.code(res.statusCode);
      reply.header("x-cm-sdk", sdk);
      reply.header("x-cm-cache", "miss");
      return body;
    } catch (err) {
      req.log.warn({ err, sdk, url: backend.url }, "capability fetch failed; returning stub");
      reply.header("x-cm-sdk", sdk);
      return stubManifest(sdk, `Backend ${sdk} at ${backend.url} unreachable: ${(err as Error).message}`);
    }
  });

  // Headers that must be stripped from the inbound request before being forwarded:
  //  - content-length: body is re-serialized below, so the inbound length may be wrong.
  //    undici recomputes it from the outgoing body.
  //  - host: refers to the gateway, not the backend; undici sets it from the target URL.
  //  - transfer-encoding / connection: hop-by-hop per RFC 7230 §6.1.
  const HOP_BY_HOP = new Set([
    "host", "content-length", "transfer-encoding", "connection",
    "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailers", "upgrade"
  ]);

  // Catch-all proxy for everything else under /api/v1/* — forwards to the selected backend.
  app.all("/api/v1/*", async (req: FastifyRequest, reply: FastifyReply) => {
    const sdk = pickSdk(req, defaultSdk);
    const backend = backends[sdk];
    if (!backend.url) {
      reply.code(503).header("x-cm-sdk", sdk);
      return {
        error: {
          code: "BackendNotConfigured",
          message: `No backend is configured for SDK '${sdk}'. Set BACKEND_${sdk.toUpperCase()}_URL in the gateway env.`,
          sdk
        }
      };
    }

    const start = process.hrtime.bigint();
    const targetUrl = `${backend.url}${req.url}`;
    try {
      const headers: Record<string, string> = {};
      for (const [k, v] of Object.entries(req.headers)) {
        if (HOP_BY_HOP.has(k.toLowerCase())) continue;
        if (Array.isArray(v)) headers[k] = v.join(", ");
        else if (typeof v === "string") headers[k] = v;
      }
      headers["x-request-id"] = req.id;
      headers["x-cm-via"] = "gateway";

      const body = ["GET", "HEAD"].includes(req.method) ? undefined :
        (typeof req.body === "string" ? req.body : JSON.stringify(req.body));

      const res = await undiciRequest(targetUrl, {
        method: req.method as never,
        headers,
        body,
        headersTimeout: 30_000,
        bodyTimeout: 30_000
      });

      const elapsedMs = Number(process.hrtime.bigint() - start) / 1_000_000;
      reply.code(res.statusCode);
      reply.header("x-cm-sdk", sdk);
      reply.header("x-cm-gateway-latency-ms", elapsedMs.toFixed(2));
      for (const [k, v] of Object.entries(res.headers)) {
        if (HOP_BY_HOP.has(k.toLowerCase())) continue;
        if (typeof v === "string") reply.header(k, v);
      }
      const buf = await res.body.arrayBuffer();
      return reply.send(Buffer.from(buf));
    } catch (err) {
      req.log.error({ err, sdk, url: targetUrl }, "proxy failed");
      reply.code(502).header("x-cm-sdk", sdk);
      return {
        error: {
          code: "BadGateway",
          message: `Backend ${sdk} at ${backend.url} is unreachable: ${(err as Error).message}`,
          sdk
        }
      };
    }
  });

  return app;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.env.PORT ?? 8000);
  const host = process.env.HOST ?? "0.0.0.0";
  const app = await build();
  app.listen({ port, host }).catch((err) => {
    app.log.error(err);
    process.exit(1);
  });
}
