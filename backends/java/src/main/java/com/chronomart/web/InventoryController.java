package com.chronomart.web;

import com.chronomart.domain.Inventory;
import com.chronomart.repo.InventoryRepo;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Inventory point-read + upsert. PK = {@code /sellerId}. Mirrors
 * {@code contracts/openapi.yaml#/paths/~1inventory~1{sellerId}~1{id}}.
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryRepo inventory;

    public InventoryController(InventoryRepo inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/{sellerId}/{id}")
    public Mono<ResponseEntity<Inventory>> get(@PathVariable String sellerId, @PathVariable String id) {
        return inventory.get(sellerId, id)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PutMapping("/{sellerId}/{id}")
    public Mono<Inventory> upsert(@PathVariable String sellerId,
                                  @PathVariable String id,
                                  @Valid @RequestBody Inventory body) {
        Inventory normalized = new Inventory(
            id, sellerId, body.productId(), body.available(), body.reserved(), body.updatedAt()
        );
        return inventory.upsert(normalized);
    }
}
