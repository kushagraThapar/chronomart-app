package com.chronomart.repo;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic, pseudo-random {@value ContainerInitializer#VECTOR_DIMENSIONS}-dimension
 * FLOAT32 unit-vector generator used to seed and probe the {@code ProductVectors}
 * container without taking a dependency on an external embedding model.
 *
 * <p>What this is and is not:
 * <ul>
 *   <li><b>Deterministic.</b> Same seed → byte-identical vector. The e2e test relies on
 *       this: seed N vectors with known seeds, search with one of them, top-1 distance
 *       MUST be exactly 0 (cosine self-distance). Any drift here is a bug.</li>
 *   <li><b>NOT semantic.</b> Two products with similar names produce vectors that are
 *       statistically uncorrelated. This is fine — we are a Cosmos SDK testing harness;
 *       the product is the wire path (provision → write → search → return ranked), not
 *       recall quality. The capability manifest labels the provider as
 *       {@code synthetic-hash-1024} to make this explicit.</li>
 * </ul>
 *
 * <p>Algorithm: 32 rounds of {@code SHA-256(seed || roundIndex)} concatenated to 1024
 * bytes, reinterpreted as 1024 unsigned-byte → signed-float values in {@code [-1, 1)},
 * then L2-normalized so cosine distance behaves identically to dot product.
 */
@Component
public class EmbeddingGenerator {

    private static final int DIMENSIONS = ContainerInitializer.VECTOR_DIMENSIONS;
    private static final int SHA256_BYTES = 32;
    private static final int ROUNDS = DIMENSIONS / SHA256_BYTES;   // 1024/32 = 32 rounds

    public float[] embed(String seed) {
        if (seed == null) {
            throw new IllegalArgumentException("embedding seed must not be null");
        }
        byte[] seedBytes = seed.getBytes(StandardCharsets.UTF_8);
        float[] out = new float[DIMENSIONS];
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (int round = 0; round < ROUNDS; round++) {
                md.reset();
                md.update(seedBytes);
                md.update(new byte[] { (byte) round });
                byte[] digest = md.digest();
                for (int i = 0; i < SHA256_BYTES; i++) {
                    // Map unsigned byte 0..255 to float in [-1, 1).
                    int u = digest[i] & 0xFF;
                    out[round * SHA256_BYTES + i] = (u - 128) / 128.0f;
                }
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable; this JVM is broken", e);
        }
        return l2Normalize(out);
    }

    private static float[] l2Normalize(float[] v) {
        double sumSq = 0.0;
        for (float f : v) sumSq += (double) f * f;
        if (sumSq == 0.0) {
            // Astronomically unlikely (would require all 1024 bytes = 128), but the test rig
            // shouldn't divide by zero — leave as-is rather than producing NaN.
            return v;
        }
        float norm = (float) Math.sqrt(sumSq);
        for (int i = 0; i < v.length; i++) v[i] /= norm;
        return v;
    }
}
