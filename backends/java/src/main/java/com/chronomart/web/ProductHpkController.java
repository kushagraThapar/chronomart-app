package com.chronomart.web;

import com.chronomart.domain.ProductHpk;
import com.chronomart.repo.ProductHpkRepo;
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
 * ProductHpk point-read + upsert over the hierarchical-PK
 * {@code (/sellerId, /categoryId, /id)} container. Mirrors
 * {@code contracts/openapi.yaml#/paths/~1products-hpk~1{sellerId}~1{categoryId}~1{id}}.
 *
 * <p>Path-var {@code @NotBlank} guards against empty HPK levels that {@code withPath()}
 * would otherwise propagate verbatim into the persisted document.
 */
@RestController
@RequestMapping("/api/v1/products-hpk")
@Validated
public class ProductHpkController {

    private final ProductHpkRepo products;

    public ProductHpkController(ProductHpkRepo products) {
        this.products = products;
    }

    @GetMapping("/{sellerId}/{categoryId}/{id}")
    public Mono<ResponseEntity<ProductHpk>> get(
        @PathVariable @NotBlank String sellerId,
        @PathVariable @NotBlank String categoryId,
        @PathVariable @NotBlank String id) {
        return products.get(sellerId, categoryId, id)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PutMapping("/{sellerId}/{categoryId}/{id}")
    public Mono<ProductHpk> upsert(
        @PathVariable @NotBlank String sellerId,
        @PathVariable @NotBlank String categoryId,
        @PathVariable @NotBlank String id,
        @Valid @RequestBody ProductHpk body) {
        return products.upsert(withPath(body, sellerId, categoryId, id));
    }

    /**
     * Path's HPK tuple + id always wins over the body — defensive against a sloppy client
     * that would otherwise upsert into a different partition. Matches the PR3 PUT convention
     * for single-PK containers (Product/Review/Inventory).
     */
    private static ProductHpk withPath(ProductHpk p, String sellerId, String categoryId, String id) {
        return new ProductHpk(
            id, sellerId, categoryId, p.name(), p.brand(), p.model(),
            p.priceUsd(), p.currency(), p.attributes(), p.tags(), p.images(),
            p.createdAt(), p.updatedAt()
        );
    }
}

