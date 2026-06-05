package com.chronomart;

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.chronomart.repo.ContainerInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Smoke test that the Spring context boots without a live Cosmos endpoint. Real
 * integration coverage lands in PR3+ once we have testcontainers + emulator wiring.
 */
@SpringBootTest(properties = {
    "chronomart.cosmos.endpoint=https://localhost:8081/",
    "chronomart.cosmos.key=test-key-not-used",
    "chronomart.cosmos.allow-invalid-certs=false",
    "chronomart.cosmos.init.enabled=false"
})
class SmokeTest {

    @MockitoBean
    CosmosAsyncClient cosmosAsyncClient;

    @MockitoBean
    CosmosAsyncDatabase cosmosAsyncDatabase;

    @MockitoBean
    ContainerInitializer containerInitializer;

    @Test
    void contextLoads() {
        // Spring context must wire — no Cosmos calls happen during boot when init is disabled.
    }
}

