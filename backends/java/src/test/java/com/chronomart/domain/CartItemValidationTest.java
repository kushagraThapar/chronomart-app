package com.chronomart.domain;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline Bean Validation tests for {@link CartItem}.
 *
 * <p>Covers the optional price/seller snapshot fields added in the cart price-snapshot change:
 * backward-compat (null snapshot), happy-path (full snapshot), and rejection of a negative price.
 * No Spring context is needed — the standard Hibernate Validator is bootstrapped directly.
 */
class CartItemValidationTest {

    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // ----- backward-compat: items without a snapshot still validate -----

    @Test
    void acceptsItemWithoutSnapshot() {
        CartItem item = new CartItem("prod-001", 2, Instant.now(), null, null);
        assertThat(validator.validate(item)).isEmpty();
    }

    @Test
    void acceptsItemWithoutAddedAt() {
        // addedAt is unannotated (nullable) — older/manual items may omit it
        CartItem item = new CartItem("prod-001", 1, null, null, null);
        assertThat(validator.validate(item)).isEmpty();
    }

    // ----- happy-path: full snapshot -----

    @Test
    void acceptsItemWithFullSnapshot() {
        CartItem item = new CartItem("prod-001", 3, Instant.now(), "seller-007", 449.99);
        assertThat(validator.validate(item)).isEmpty();
    }

    @Test
    void acceptsItemWithZeroPrice() {
        // @PositiveOrZero means 0 is valid (free/promotional item)
        CartItem item = new CartItem("prod-001", 1, Instant.now(), "seller-007", 0.0);
        assertThat(validator.validate(item)).isEmpty();
    }

    // ----- constraint violations -----

    @Test
    void rejectsNegativeUnitPrice() {
        CartItem item = new CartItem("prod-001", 2, Instant.now(), "seller-007", -1.0);
        Set<ConstraintViolation<CartItem>> violations = validator.validate(item);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString())
            .isEqualTo("unitPriceUsd");
    }

    @Test
    void rejectsBlankProductId() {
        CartItem item = new CartItem("", 1, Instant.now(), null, null);
        Set<ConstraintViolation<CartItem>> violations = validator.validate(item);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString())
            .isEqualTo("productId");
    }

    @Test
    void rejectsZeroQty() {
        CartItem item = new CartItem("prod-001", 0, Instant.now(), null, null);
        Set<ConstraintViolation<CartItem>> violations = validator.validate(item);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString())
            .isEqualTo("qty");
    }
}
