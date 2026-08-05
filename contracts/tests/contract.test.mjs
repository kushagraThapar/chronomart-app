import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import test from "node:test";

const baseUrl = (process.env.CHRONOMART_BASE_URL ?? "http://localhost:8000/api/v1").replace(/\/$/, "");
const sdks = (process.env.CHRONOMART_SDKS ?? "java,dotnet")
  .split(",")
  .map((sdk) => sdk.trim())
  .filter(Boolean);

const requiredCoreFeatures = [
  "pointCrud",
  "queries",
  "queriesCrossPartition",
  "bulk",
  "transactionalBatch",
  "changeFeedPull",
  "hierarchicalPk",
  "ttl",
  "patch",
  "feedRanges"
];

async function request(sdk, path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      accept: "application/json",
      "content-type": "application/json",
      "x-cosmos-sdk": sdk,
      ...options.headers
    }
  });

  const text = await response.text();
  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      assert.fail(`${sdk} ${options.method ?? "GET"} ${path} returned non-JSON: ${text.slice(0, 500)}`);
    }
  }
  return { response, body };
}

function assertSuccess(result, context) {
  assert.ok(
    result.response.ok,
    `${context}: expected 2xx, got ${result.response.status} ${JSON.stringify(result.body)}`
  );
}

function assertSdkHeader(result, sdk) {
  const value = result.response.headers.get("x-cm-sdk");
  assert.ok(value === sdk || value?.startsWith(`${sdk} `), `unexpected x-cm-sdk header: ${value}`);
}

for (const sdk of sdks) {
  test(`${sdk}: ChronoMart v1 contract`, async (t) => {
    const suffix = randomUUID();
    const sellerId = `contract-seller-${suffix}`;
    const inventoryId = `contract-inventory-${suffix}`;

    const capabilitiesResult = await request(sdk, "/_meta/capabilities");
    assertSuccess(capabilitiesResult, `${sdk} capabilities`);
    assertSdkHeader(capabilitiesResult, sdk);
    const capabilities = capabilitiesResult.body;
    assert.equal(capabilities.sdk, sdk);
    assert.ok(typeof capabilities.sdkVersion === "string" && capabilities.sdkVersion.length > 0);
    assert.ok(capabilities.apiVersions.includes("v1"));
    for (const feature of requiredCoreFeatures) {
      assert.equal(capabilities.features[feature], true, `${sdk} must advertise ${feature}=true`);
    }

    await t.test("seeded seller list", async () => {
      const result = await request(sdk, "/sellers?limit=2");
      assertSuccess(result, `${sdk} list sellers`);
      assertSdkHeader(result, sdk);
      assert.ok(Array.isArray(result.body));
      assert.ok(result.body.length > 0, "seed data must expose at least one seller");
      assert.ok(result.body.length <= 2);
      assert.equal(typeof result.body[0].id, "string");
    });

    await t.test("point upsert and read", async () => {
      const document = {
        id: inventoryId,
        sellerId,
        productId: `contract-product-${suffix}`,
        available: 7,
        reserved: 1,
        updatedAt: new Date().toISOString()
      };
      const upsert = await request(sdk, `/inventory/${sellerId}/${inventoryId}`, {
        method: "PUT",
        body: JSON.stringify(document)
      });
      assertSuccess(upsert, `${sdk} upsert inventory`);
      assert.deepEqual(
        {
          id: upsert.body.id,
          sellerId: upsert.body.sellerId,
          productId: upsert.body.productId,
          available: upsert.body.available,
          reserved: upsert.body.reserved
        },
        {
          id: document.id,
          sellerId: document.sellerId,
          productId: document.productId,
          available: document.available,
          reserved: document.reserved
        }
      );

      const read = await request(sdk, `/inventory/${sellerId}/${inventoryId}`);
      assertSuccess(read, `${sdk} read inventory`);
      assert.equal(read.body.id, inventoryId);
      assert.equal(read.body.sellerId, sellerId);
    });

    await t.test("parameterized partition query", async () => {
      const result = await request(sdk, "/queries/run", {
        method: "POST",
        body: JSON.stringify({
          container: "Inventory",
          query: "SELECT * FROM c WHERE c.sellerId = @sellerId AND c.id = @id",
          parameters: [
            { name: "@sellerId", value: sellerId },
            { name: "@id", value: inventoryId }
          ],
          partitionKey: sellerId,
          pageSize: 10
        })
      });
      assertSuccess(result, `${sdk} query inventory`);
      assert.equal(result.body.items.length, 1);
      assert.equal(result.body.items[0].id, inventoryId);
      assert.equal(typeof result.body.requestCharge, "number");
    });

    await t.test("patch", async () => {
      const result = await request(sdk, "/patch", {
        method: "POST",
        body: JSON.stringify({
          container: "Inventory",
          id: inventoryId,
          partitionKey: sellerId,
          operations: [{ op: "increment", path: "/available", value: 2 }]
        })
      });
      assertSuccess(result, `${sdk} patch inventory`);
      assert.equal(result.body.available, 9);
    });

    await t.test("bulk upsert", async () => {
      const operations = [0, 1].map((index) => ({
        op: "upsert",
        partitionKey: sellerId,
        document: {
          id: `contract-bulk-${suffix}-${index}`,
          sellerId,
          productId: `contract-bulk-product-${index}`,
          available: index + 1,
          reserved: 0,
          updatedAt: new Date().toISOString()
        }
      }));
      const result = await request(sdk, "/bulk", {
        method: "POST",
        body: JSON.stringify({ container: "Inventory", operations, maxConcurrency: 2 })
      });
      assertSuccess(result, `${sdk} bulk inventory`);
      assert.equal(result.body.results.length, operations.length);
      assert.ok(result.body.results.every((item) => item.statusCode >= 200 && item.statusCode < 300));
      assert.equal(typeof result.body.totalRequestCharge, "number");
    });

    await t.test("transactional batch", async () => {
      const operations = [0, 1].map((index) => ({
        op: "upsert",
        document: {
          id: `contract-batch-${suffix}-${index}`,
          sellerId,
          productId: `contract-batch-product-${index}`,
          available: 10 + index,
          reserved: 0,
          updatedAt: new Date().toISOString()
        }
      }));
      const result = await request(sdk, "/batch", {
        method: "POST",
        body: JSON.stringify({ container: "Inventory", partitionKey: sellerId, operations })
      });
      assertSuccess(result, `${sdk} batch inventory`);
      assert.equal(result.body.success, true);
      assert.equal(result.body.results.length, operations.length);
    });

    await t.test("hierarchical partition-key point operations", async () => {
      const id = `contract-hpk-${suffix}`;
      const categoryId = "contract";
      const product = {
        id,
        sellerId,
        categoryId,
        name: "Contract Test Watch",
        priceUsd: 123.45,
        currency: "USD",
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString()
      };
      const upsert = await request(sdk, `/products-hpk/${sellerId}/${categoryId}/${id}`, {
        method: "PUT",
        body: JSON.stringify(product)
      });
      assertSuccess(upsert, `${sdk} upsert HPK product`);

      const read = await request(sdk, `/products-hpk/${sellerId}/${categoryId}/${id}`);
      assertSuccess(read, `${sdk} read HPK product`);
      assert.equal(read.body.id, id);
      assert.equal(read.body.categoryId, categoryId);
    });

    await t.test("feed ranges and change-feed continuation", async () => {
      const ranges = await request(sdk, "/_meta/feed-ranges?container=Inventory");
      assertSuccess(ranges, `${sdk} feed ranges`);
      assert.ok(Array.isArray(ranges.body));
      assert.ok(ranges.body.length > 0);
      assert.ok(ranges.body.every((range) => typeof range.opaque === "string"));

      const changeFeed = await request(sdk, "/changefeed/pull", {
        method: "POST",
        body: JSON.stringify({
          container: "Inventory",
          startFrom: "now",
          partitionKey: sellerId,
          pageSize: 10
        })
      });
      assertSuccess(changeFeed, `${sdk} change feed`);
      assert.ok(Array.isArray(changeFeed.body.items));
      assert.equal(typeof changeFeed.body.continuation, "string");
      if (changeFeed.body.notModified !== undefined) {
        assert.equal(typeof changeFeed.body.notModified, "boolean");
      }

      const resumed = await request(sdk, "/changefeed/pull", {
        method: "POST",
        body: JSON.stringify({
          container: "Inventory",
          startFrom: "continuation",
          continuation: changeFeed.body.continuation,
          pageSize: 10
        })
      });
      assertSuccess(resumed, `${sdk} resume change feed`);
      assert.equal(typeof resumed.body.continuation, "string");
    });

    await t.test("diagnostic surfaces", async () => {
      const diagnostics = await request(sdk, "/_meta/diagnostics?last=10");
      assertSuccess(diagnostics, `${sdk} diagnostics`);
      assert.ok(Array.isArray(diagnostics.body));

      if (capabilities.features.cacheInspection) {
        const caches = await request(sdk, "/_meta/caches");
        assertSuccess(caches, `${sdk} caches`);
        assert.ok(Array.isArray(caches.body.containerCache));
        assert.ok(Array.isArray(caches.body.pkRangeCache));
      }
    });

    await t.test("invalid container uses structured error", async () => {
      const result = await request(sdk, "/queries/run", {
        method: "POST",
        body: JSON.stringify({
          container: "NotAChronoMartContainer",
          query: "SELECT * FROM c",
          enableCrossPartition: true
        })
      });
      assert.equal(result.response.status, 400);
      assert.equal(typeof result.body.error.code, "string");
      assert.equal(typeof result.body.error.message, "string");
      assert.equal(result.body.error.sdk, sdk);
    });
  });
}
