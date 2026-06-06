package com.chronomart.web;

import com.chronomart.domain.Customer;
import com.chronomart.repo.CustomerRepo;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Customer create + point-read. PK = {@code /id}. Mirrors
 * {@code contracts/openapi.yaml#/paths/~1customers} and
 * {@code contracts/openapi.yaml#/paths/~1customers~1{id}}.
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerRepo customers;

    public CustomerController(CustomerRepo customers) {
        this.customers = customers;
    }

    @PostMapping
    public Mono<ResponseEntity<Customer>> create(@Valid @RequestBody Customer customer) {
        return customers.create(customer)
            .map(c -> ResponseEntity.status(HttpStatus.CREATED).body(c));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<Customer>> get(@PathVariable String id) {
        return customers.get(id)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
