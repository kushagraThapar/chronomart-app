# ChronoMart seed scripts

These `.csh` (cosmoshell) scripts run **automatically** when the vNext emulator container
starts with `ENABLE_INIT_DATA=true` and this directory bind-mounted at `/init`. They run
in alphabetical order, **before** the emulator accepts external requests, so every
`docker compose up` lands a deterministic dataset.

## What they do

- `01-init.csh` — creates the `ChronoMart` database and **all the schema cosmoshell can
  express**: simple-PK containers (`Sellers`, `Products`, `Inventory`, `Customers`,
  `Reviews`, `Cart`), hierarchical-PK containers (`ProductsHpk`, `Orders`) and the
  `ChangeFeedLease` container.
- `02-load-sellers.csh` — inserts 5 sample sellers.
- `03-load-products.csh` — inserts ~20 sample products across those sellers.
- `04-load-customers.csh` — inserts 5 sample customers.

## What they intentionally do NOT do

Containers / policies cosmoshell can't express yet are created by the **backends on startup**
via the native SDK. They are:

- `ProductVectors` — vector embedding policy + DiskANN.
- Default container TTL on `Cart` (the `mkcon` flag for TTL isn't available; backends set
  `defaultTimeToLive=604800` on the Cart container as part of their idempotent startup so
  per-item TTL on cart documents actually takes effect).

Backends use `createContainerIfNotExists`-style APIs, so the seed scripts are idempotent
with backend startup: if a container already exists, the backend skips creation.

## Override / disable

Don't want seed data? Drop `ENABLE_INIT_DATA=true` from `docker-compose.yml` and the
emulator boots empty.
