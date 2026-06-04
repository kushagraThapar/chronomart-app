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
    Init init
) {

    public ChronomartProperties {
        if (database == null || database.isBlank()) database = "ChronoMart";
        if (authMode == null || authMode.isBlank()) authMode = "key";
        if (containers == null) containers = new Containers(null, null, null, null, null, null, null, null, null, null);
        if (init == null) init = new Init(true, true);
    }

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
        public Containers {
            if (sellers == null) sellers = "Sellers";
            if (products == null) products = "Products";
            if (productsHpk == null) productsHpk = "ProductsHpk";
            if (customers == null) customers = "Customers";
            if (orders == null) orders = "Orders";
            if (reviews == null) reviews = "Reviews";
            if (cart == null) cart = "Cart";
            if (inventory == null) inventory = "Inventory";
            if (productVectors == null) productVectors = "ProductVectors";
            if (changeFeedLease == null) changeFeedLease = "ChangeFeedLease";
        }
    }

    public record Init(
        boolean enabled,
        boolean createVectorContainer
    ) {}
}
