package com.chronomart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Top-level ChronoMart configuration. Bound from environment variables / application.yaml
 * via Spring Boot's {@code @ConfigurationProperties}.
 *
 * <p>Examples (env):
 * <pre>
 *   CHRONOMART_COSMOS_ENDPOINT=https://cosmos-emulator:8081/
 *   CHRONOMART_COSMOS_KEY=...
 *   CHRONOMART_COSMOS_DATABASE=ChronoMart
 *   CHRONOMART_COSMOS_ALLOW_INVALID_CERTS=true
 *   CHRONOMART_COSMOS_EMULATOR_HOST=cosmos-emulator
 * </pre>
 */
@ConfigurationProperties(prefix = "chronomart.cosmos")
public record ChronomartProperties(
    String endpoint,
    String key,
    String database,
    String authMode,
    boolean allowInvalidCerts,
    String emulatorHost,
    Containers containers,
    ContainerInit containerInit
) {

    public ChronomartProperties {
        if (database == null || database.isBlank()) database = "ChronoMart";
        if (authMode == null || authMode.isBlank()) authMode = "key";
        if (containers == null) containers = Containers.defaults();
        if (containerInit == null) containerInit = ContainerInit.defaults();
    }

    /**
     * Container name overrides. Each field is the logical container name; if a field is
     * unset in YAML / env vars, it falls back to the canonical default below (which is also
     * the convention used by the cosmoshell seed scripts in {@code scripts/seed/}).
     * Call {@link #defaults()} when you need a fully-populated instance without writing
     * the ten {@code null}s yourself.
     */
    public record Containers(
        String sellers,
        String products,
        String productsHpk,
        String customers,
        String orders,
        String reviews,
        String cart,
        String inventory,
        String productVectors,
        String changeFeedLease
    ) {
        static final String DEFAULT_SELLERS           = "Sellers";
        static final String DEFAULT_PRODUCTS          = "Products";
        static final String DEFAULT_PRODUCTS_HPK      = "ProductsHpk";
        static final String DEFAULT_CUSTOMERS         = "Customers";
        static final String DEFAULT_ORDERS            = "Orders";
        static final String DEFAULT_REVIEWS           = "Reviews";
        static final String DEFAULT_CART              = "Cart";
        static final String DEFAULT_INVENTORY         = "Inventory";
        static final String DEFAULT_PRODUCT_VECTORS   = "ProductVectors";
        static final String DEFAULT_CHANGE_FEED_LEASE = "ChangeFeedLease";

        public Containers {
            // Per-field null fill-in so partial YAML overrides work
            // (e.g. user sets only `containers.products: MyProds` and leaves others to default).
            if (sellers == null)         sellers         = DEFAULT_SELLERS;
            if (products == null)        products        = DEFAULT_PRODUCTS;
            if (productsHpk == null)     productsHpk     = DEFAULT_PRODUCTS_HPK;
            if (customers == null)       customers       = DEFAULT_CUSTOMERS;
            if (orders == null)          orders          = DEFAULT_ORDERS;
            if (reviews == null)         reviews         = DEFAULT_REVIEWS;
            if (cart == null)            cart            = DEFAULT_CART;
            if (inventory == null)       inventory       = DEFAULT_INVENTORY;
            if (productVectors == null)  productVectors  = DEFAULT_PRODUCT_VECTORS;
            if (changeFeedLease == null) changeFeedLease = DEFAULT_CHANGE_FEED_LEASE;
        }

        public static Containers defaults() {
            return new Containers(
                DEFAULT_SELLERS, DEFAULT_PRODUCTS, DEFAULT_PRODUCTS_HPK,
                DEFAULT_CUSTOMERS, DEFAULT_ORDERS, DEFAULT_REVIEWS,
                DEFAULT_CART, DEFAULT_INVENTORY, DEFAULT_PRODUCT_VECTORS,
                DEFAULT_CHANGE_FEED_LEASE
            );
        }
    }

    /**
     * Startup container-provisioning settings consumed by {@code ContainerInitializer}.
     *
     * <ul>
     *   <li>{@code enabled} — when {@code false}, the initializer is a no-op and the
     *       backend assumes containers were pre-created (e.g. by cosmoshell seed scripts
     *       or a previous run). Useful in CI / read-only scenarios.</li>
     *   <li>{@code createVectorContainer} — when {@code true}, attempts to provision
     *       {@code ProductVectors} with the DiskANN vector index policy. Off by default
     *       until vNext-emulator DiskANN support is confirmed in Phase 6.</li>
     * </ul>
     */
    public record ContainerInit(
        boolean enabled,
        boolean createVectorContainer
    ) {
        public static ContainerInit defaults() {
            return new ContainerInit(true, false);
        }
    }
}
