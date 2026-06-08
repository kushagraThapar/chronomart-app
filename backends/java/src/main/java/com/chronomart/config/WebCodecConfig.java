package com.chronomart.config;

import org.springframework.boot.http.codec.CodecCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires WebFlux codec settings that Spring Boot 4 no longer exposes via property keys.
 *
 * <p>Specifically raises the in-memory buffer ceiling from the default 256 KB to 16 MB so
 * the harness can ingest realistic request bodies. The two paths that previously hit
 * {@code DataBufferLimitException}:
 * <ul>
 *   <li>{@code POST /api/v1/bulk} with vector documents — 1024 FLOAT32 + metadata per
 *       doc serializes to ~20 KB; a 10-doc batch sits right at the default ceiling.</li>
 *   <li>{@code POST /api/v1/vector/search} with a 1024-dim query vector — by itself
 *       ~20 KB, well under the ceiling, but adding a continuation/filter for future
 *       PRs would close that margin.</li>
 * </ul>
 *
 * <p>16 MB is generous but matches Spring's documented sane upper bound for in-memory
 * request bodies on WebFlux servers. If a future workload needs more, the right move is
 * to switch that endpoint to a streaming decoder, not to raise this further.
 */
@Configuration
public class WebCodecConfig {

    private static final int MAX_IN_MEMORY_BYTES = 16 * 1024 * 1024;

    @Bean
    public CodecCustomizer maxInMemoryCodecCustomizer() {
        return configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES);
    }
}
