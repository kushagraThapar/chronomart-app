package com.chronomart.web;

import com.chronomart.domain.Cart;
import com.chronomart.repo.CartRepo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Cart point-read + upsert (PK = {@code /customerId}). Mirrors
 * {@code contracts/openapi.yaml#/paths/~1cart~1{customerId}}.
 *
 * <p>One document per customer: the document's {@code id} is set to {@code customerId} so
 * a {@code readItem} can route directly to the single partition that holds it.
 * Per-document {@code ttl} (seconds) overrides the container's 7-day default, letting the
 * UI demonstrate TTL semantics interactively (e.g., {@code ttl: 5} to expire in 5 seconds,
 * {@code ttl: -1} to disable TTL on this doc).
 */
@RestController
@RequestMapping("/api/v1/cart")
@Validated
public class CartController {

    private final CartRepo carts;

    public CartController(CartRepo carts) {
        this.carts = carts;
    }

    @GetMapping("/{customerId}")
    public Mono<ResponseEntity<Cart>> get(@PathVariable @NotBlank String customerId) {
        return carts.get(customerId)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PutMapping("/{customerId}")
    public Mono<Cart> upsert(@PathVariable @NotBlank String customerId,
                             @Valid @RequestBody Cart body) {
        return carts.upsert(withPath(body, customerId));
    }

    /**
     * Path's {@code customerId} wins over body; doc {@code id} is forced to {@code customerId}
     * so each customer has exactly one cart document and {@code readItem} can use the same
     * value for both {@code id} and {@code partitionKey}.
     */
    private static Cart withPath(Cart c, String customerId) {
        return new Cart(customerId, customerId, c.items(), c.updatedAt(), c.ttl());
    }
}

