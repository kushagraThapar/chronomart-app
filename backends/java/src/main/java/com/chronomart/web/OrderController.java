package com.chronomart.web;

import com.chronomart.domain.Order;
import com.chronomart.repo.OrderRepo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
 * Order point-read + upsert over the hierarchical-PK
 * {@code (/customerId, /yearMonth, /id)} container. Mirrors
 * {@code contracts/openapi.yaml#/paths/~1orders~1{customerId}~1{yearMonth}~1{id}}.
 *
 * <p>Path-variable validation runs <em>before</em> {@code @Valid @RequestBody} validation,
 * which matters here because {@code withPath()} overwrites the body's {@code yearMonth}
 * with the path value: without {@code @Pattern} on the path var, a request like
 * {@code PUT /orders/cust/not-a-month/o1} with a well-formed body would persist a
 * document with an invalid HPK level.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Validated
public class OrderController {

    private final OrderRepo orders;

    public OrderController(OrderRepo orders) {
        this.orders = orders;
    }

    @GetMapping("/{customerId}/{yearMonth}/{id}")
    public Mono<ResponseEntity<Order>> get(
        @PathVariable @NotBlank String customerId,
        @PathVariable @NotBlank @Pattern(regexp = "^[0-9]{4}-[0-9]{2}$",
                                         message = "yearMonth must be YYYY-MM") String yearMonth,
        @PathVariable @NotBlank String id) {
        return orders.get(customerId, yearMonth, id)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PutMapping("/{customerId}/{yearMonth}/{id}")
    public Mono<Order> upsert(
        @PathVariable @NotBlank String customerId,
        @PathVariable @NotBlank @Pattern(regexp = "^[0-9]{4}-[0-9]{2}$",
                                         message = "yearMonth must be YYYY-MM") String yearMonth,
        @PathVariable @NotBlank String id,
        @Valid @RequestBody Order body) {
        return orders.upsert(withPath(body, customerId, yearMonth, id));
    }

    /**
     * Path's HPK tuple + id always wins over the body — defensive against a sloppy client
     * that would otherwise upsert into a different partition.
     */
    private static Order withPath(Order o, String customerId, String yearMonth, String id) {
        return new Order(
            id, customerId, yearMonth, o.status(), o.items(),
            o.totalUsd(), o.createdAt(), o.shippedAt()
        );
    }
}

