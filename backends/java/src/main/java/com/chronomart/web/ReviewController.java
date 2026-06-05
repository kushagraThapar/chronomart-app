package com.chronomart.web;

import com.chronomart.domain.Review;
import com.chronomart.repo.ReviewRepo;
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
 * Review point-read + upsert. PK = {@code /productId}. Mirrors
 * {@code contracts/openapi.yaml#/paths/~1reviews~1{productId}~1{id}}.
 */
@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    private final ReviewRepo reviews;

    public ReviewController(ReviewRepo reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/{productId}/{id}")
    public Mono<ResponseEntity<Review>> get(@PathVariable String productId, @PathVariable String id) {
        return reviews.get(productId, id)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PutMapping("/{productId}/{id}")
    public Mono<Review> upsert(@PathVariable String productId,
                               @PathVariable String id,
                               @Valid @RequestBody Review review) {
        Review normalized = new Review(
            id, productId, review.customerId(), review.rating(),
            review.title(), review.body(), review.createdAt()
        );
        return reviews.upsert(normalized);
    }
}
