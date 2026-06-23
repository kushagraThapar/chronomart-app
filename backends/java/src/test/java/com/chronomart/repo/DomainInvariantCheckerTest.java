package com.chronomart.repo;

import com.chronomart.web.dto.WorkloadAnomaly;
import com.chronomart.web.dto.WorkloadVerification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the L2 domain-invariant checks ({@link DomainInvariantChecker}). Each feeds a
 * hand-built read-back document and asserts the right {@code DOMAIN_INVARIANT_*} anomaly — or none.
 */
class DomainInvariantCheckerTest {

    private final DomainInvariantChecker checker = new DomainInvariantChecker();

    private WorkloadVerificationState state() {
        return new WorkloadVerificationState("run-test", new WorkloadVerification(
            true, "session", null, new WorkloadVerification.Keyspace("wl", 10), 1.0, null, null, null));
    }

    private Map<String, Object> item(int qty, double price) {
        return Map.of("productId", "prod-001", "sellerId", "seller-001", "qty", qty, "unitPriceUsd", price);
    }

    private Map<String, Object> order(double total, List<Map<String, Object>> items) {
        return Map.of("id", "ord-1", "customerId", "cust-1", "yearMonth", "2026-06",
            "items", items, "totalUsd", total);
    }

    // ----- order total -----

    @Test
    void cleanOrderRaisesNothing() {
        WorkloadVerificationState s = state();
        checker.checkOrder(s, "checkout", "Orders", order(2 * 100.0 + 1 * 250.0, List.of(item(2, 100.0), item(1, 250.0))));
        assertThat(s.anomalyCount()).isZero();
    }

    @Test
    void wrongTotalIsFlagged() {
        WorkloadVerificationState s = state();
        checker.checkOrder(s, "checkout", "Orders", order(999.0, List.of(item(2, 100.0))));   // should be 200
        assertThat(s.anomalies(0, 10)).singleElement().satisfies(x -> {
            assertThat(x.code()).isEqualTo("DOMAIN_INVARIANT_ORDER_TOTAL");
            assertThat(x.severity()).isEqualTo(WorkloadAnomaly.SEVERITY_ERROR);
        });
    }

    @Test
    void totalWithinEpsilonIsClean() {
        WorkloadVerificationState s = state();
        checker.checkOrder(s, "checkout", "Orders", order(200.0005, List.of(item(2, 100.0))));
        assertThat(s.anomalyCount()).isZero();
    }

    @Test
    void zeroQtyIsFlagged() {
        WorkloadVerificationState s = state();
        checker.checkOrder(s, "checkout", "Orders", order(0.0, List.of(item(0, 100.0))));
        assertThat(s.anomalies(0, 10)).singleElement().satisfies(x ->
            assertThat(x.code()).isEqualTo("DOMAIN_INVARIANT_ITEM_QTY"));
    }

    @Test
    void negativePriceIsFlagged() {
        WorkloadVerificationState s = state();
        checker.checkOrder(s, "checkout", "Orders", order(-100.0, List.of(item(1, -100.0))));
        assertThat(s.anomalies(0, 10)).singleElement().satisfies(x ->
            assertThat(x.code()).isEqualTo("DOMAIN_INVARIANT_ITEM_PRICE"));
    }

    @Test
    void missingItemsIsFlagged() {
        WorkloadVerificationState s = state();
        checker.checkOrder(s, "checkout", "Orders", Map.of("id", "ord-2", "totalUsd", 0.0));
        assertThat(s.anomalies(0, 10)).singleElement().satisfies(x ->
            assertThat(x.code()).isEqualTo("DOMAIN_INVARIANT_ORDER_SHAPE"));
    }

    // ----- cart cleared -----

    @Test
    void clearedCartRaisesNothing() {
        WorkloadVerificationState s = state();
        checker.checkCartCleared(s, "checkout", "Cart", Map.of("id", "cust-1", "items", List.of()));
        assertThat(s.anomalyCount()).isZero();
    }

    @Test
    void nonEmptyCartAfterCheckoutIsFlagged() {
        WorkloadVerificationState s = state();
        checker.checkCartCleared(s, "checkout", "Cart",
            Map.of("id", "cust-1", "items", List.of(item(1, 100.0))));
        assertThat(s.anomalies(0, 10)).singleElement().satisfies(x -> {
            assertThat(x.code()).isEqualTo("DOMAIN_INVARIANT_CART_NOT_CLEARED");
            assertThat(x.key()).isEqualTo("cust-1");
        });
    }
}
