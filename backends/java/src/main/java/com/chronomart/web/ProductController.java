package com.chronomart.web;

import com.chronomart.domain.Product;
import com.chronomart.repo.ProductRepo;
import com.chronomart.web.dto.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Product CRUD against {@code Products} (PK = {@code /sellerId}). Mirrors
 * {@code contracts/openapi.yaml#/paths/~1products} and
 * {@code contracts/openapi.yaml#/paths/~1products~1{sellerId}~1{id}}.
 */
@RestController
@RequestMapping("/api/v1/products")
@Validated
public class ProductController {

    private final ProductRepo products;

    public ProductController(ProductRepo products) {
        this.products = products;
    }

    @GetMapping
    public Mono<Page<Product>> list(
        @RequestParam(required = false) String sellerId,
        @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int pageSize,
        @RequestParam(required = false) String continuation) {
        return products.list(sellerId, pageSize, continuation);
    }

    @PostMapping
    public Mono<ResponseEntity<Product>> create(@Valid @RequestBody Product product) {
        return products.create(product)
            .map(p -> ResponseEntity.status(HttpStatus.CREATED).body(p));
    }

    @GetMapping("/{sellerId}/{id}")
    public Mono<ResponseEntity<Product>> get(@PathVariable String sellerId, @PathVariable String id) {
        return products.get(sellerId, id)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PutMapping("/{sellerId}/{id}")
    public Mono<Product> upsert(@PathVariable String sellerId,
                                @PathVariable String id,
                                @Valid @RequestBody Product product) {
        return products.upsert(withPath(product, sellerId, id));
    }

    @DeleteMapping("/{sellerId}/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable String sellerId, @PathVariable String id) {
        return products.delete(sellerId, id)
            .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    /**
     * For PUT we trust the path-segment PK + id over whatever the body claims, so a sloppy
     * client cannot accidentally upsert into a different partition. The OpenAPI's PUT
     * response shape is the persisted entity, so the canonical id/sellerId come from the path.
     */
    private static Product withPath(Product p, String sellerId, String id) {
        return new Product(
            id, sellerId, p.categoryId(), p.name(), p.brand(), p.model(),
            p.priceUsd(), p.currency(), p.attributes(), p.tags(), p.images(),
            p.createdAt(), p.updatedAt()
        );
    }
}
