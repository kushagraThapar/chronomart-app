package com.chronomart.web;

import com.chronomart.domain.Seller;
import com.chronomart.repo.SellerRepo;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read-only sellers endpoint. Full CRUD + a generic {@code /queries/run} land in PR3.
 *
 * <p>This is the single Cosmos-backed endpoint in PR2 — it proves the end-to-end wiring
 * (container -> repo -> controller -> gateway -> UI) without expanding the surface beyond
 * what the rubber-duck recommended for a "thin slice" foundation PR.
 */
@RestController
@RequestMapping("/api/v1/sellers")
@Validated
public class SellerController {

    private final SellerRepo sellers;

    public SellerController(SellerRepo sellers) {
        this.sellers = sellers;
    }

    @GetMapping
    public Flux<Seller> list(@RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit) {
        return sellers.list(limit);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<Seller>> get(@PathVariable String id) {
        return sellers.get(id)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
