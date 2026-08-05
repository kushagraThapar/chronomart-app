# Cross-SDK contract tests

The suite exercises the same ChronoMart v1 requests through the gateway for every
implemented SDK backend. It uses only Node.js built-ins.

```bash
CHRONOMART_BASE_URL=http://localhost:8000/api/v1 \
CHRONOMART_SDKS=java,dotnet \
npm test --prefix contracts/tests
```

The tests require a provisioned ChronoMart database and validate representative
point CRUD, parameterized query, patch, bulk, transactional batch, hierarchical
partition keys, feed ranges, pull change feed, diagnostics, cache inspection, and
structured error behavior. Optional capabilities are tested only when advertised;
the Phase 2 core parity capabilities are mandatory.
