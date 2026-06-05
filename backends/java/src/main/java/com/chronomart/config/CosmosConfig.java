package com.chronomart.config;

import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * Builds the {@link CosmosAsyncClient} that every repository shares.
 *
 * <p>vNext-emulator gotchas handled here:
 * <ul>
 *   <li>Default mode is forced to <em>gateway</em> when talking to the emulator —
 *       direct mode requires extra TLS / port-mapping work that adds no value locally.</li>
 *   <li>Self-signed cert: when {@code allowInvalidCerts=true}, the SDK env vars
 *       {@code COSMOS_EMULATOR_SERVER_CERTIFICATE_VALIDATION_DISABLED=true} +
 *       {@code COSMOS_EMULATOR_HOST=<host>} are toggled via system properties so the SDK's
 *       own {@code isEmulatorHost(uri)} check matches our Docker hostname (defaults are
 *       only localhost/127.0.0.1/::1).</li>
 *   <li>Guardrail: refusing to disable cert validation against anything that looks like
 *       a real Cosmos endpoint.</li>
 * </ul>
 */
@Configuration
public class CosmosConfig {

    private static final Logger log = LoggerFactory.getLogger(CosmosConfig.class);

    @Bean
    public CosmosAsyncClient cosmosAsyncClient(ChronomartProperties props) {
        if (props.endpoint() == null || props.endpoint().isBlank()) {
            throw new IllegalStateException("chronomart.cosmos.endpoint must be set");
        }
        if (!"key".equalsIgnoreCase(props.authMode())) {
            throw new IllegalStateException("Only authMode=key is supported in Phase 1");
        }
        if (props.key() == null || props.key().isBlank()) {
            throw new IllegalStateException("chronomart.cosmos.key must be set when authMode=key");
        }

        URI uri = parseEndpoint(props.endpoint());
        boolean looksLikeEmulator = isEmulatorLike(uri, props.emulatorHost());

        if (props.allowInvalidCerts()) {
            if (!looksLikeEmulator) {
                throw new IllegalStateException(
                    "Refusing to disable server certificate validation against a non-emulator endpoint: "
                        + uri + ". Unset CHRONOMART_COSMOS_ALLOW_INVALID_CERTS or point at the emulator.");
            }
            // The SDK's Configs class reads these into static-final fields at class-load time.
            // Inside Docker we always set the matching OS env vars
            // (COSMOS_EMULATOR_HOST / COSMOS_EMULATOR_SERVER_CERTIFICATE_VALIDATION_DISABLED) so
            // the static initializers pick them up regardless of load order — that path is
            // reliable. The System.setProperty calls below are a best-effort fallback for purely
            // programmatic dev runs (no env vars) and are only effective if no SDK class has
            // been loaded yet.
            System.setProperty("COSMOS.EMULATOR_SERVER_CERTIFICATE_VALIDATION_DISABLED", "true");
            if (props.emulatorHost() != null && !props.emulatorHost().isBlank()) {
                System.setProperty("COSMOS.EMULATOR_HOST", props.emulatorHost());
            }
            log.warn("Emulator-mode TLS bypass ENABLED for endpoint={} host={} — never use against a real Cosmos account.",
                uri, props.emulatorHost());
        }

        String chronomartVersion = Objects.requireNonNullElse(
            getClass().getPackage().getImplementationVersion(), "dev");
        CosmosClientBuilder builder = new CosmosClientBuilder()
            .endpoint(props.endpoint())
            .key(props.key())
            .consistencyLevel(ConsistencyLevel.SESSION)
            .userAgentSuffix("chronomart-java/" + chronomartVersion)
            .contentResponseOnWriteEnabled(true);

        if (looksLikeEmulator) {
            // Gateway mode avoids the direct-mode port mapping song & dance against the emulator.
            builder.gatewayMode();
        }

        log.info("Building CosmosAsyncClient endpoint={} mode={} emulatorLike={}",
            props.endpoint(), looksLikeEmulator ? "gateway" : "default", looksLikeEmulator);

        return builder.buildAsyncClient();
    }

    @Bean
    public CosmosAsyncDatabase cosmosAsyncDatabase(CosmosAsyncClient client, ChronomartProperties props) {
        return client.getDatabase(props.database());
    }

    private static URI parseEndpoint(String endpoint) {
        try {
            return new URI(endpoint);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid chronomart.cosmos.endpoint: " + endpoint, e);
        }
    }

    private static boolean isEmulatorLike(URI uri, String configuredEmulatorHost) {
        String host = uri.getHost();
        if (host == null) return false;
        // URI.getHost() returns the bare form (no brackets) for IPv6 literals.
        if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
            || "::1".equals(host)) {
            return true;
        }
        return configuredEmulatorHost != null && configuredEmulatorHost.equalsIgnoreCase(host);
    }
}
