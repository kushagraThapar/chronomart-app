package com.chronomart.repo;

import com.chronomart.web.dto.WorkloadAnomaly;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * L2 of the workload oracle: business-level invariants on real marketplace documents, checked on
 * read-back so an SDK round-trip that corrupts a document (precision loss, truncated/​reordered
 * arrays, dropped fields, type coercion) is caught even when the wire status was 200.
 *
 * <p>Unlike L0/L1 (which operate on the self-verifying keyspace), these run on actual {@code Order}
 * and {@code Cart} shapes produced by the {@code checkout} workload op. Records anomalies into the
 * run's {@link WorkloadVerificationState} sink.
 *
 * <p>All field access uses {@code instanceof Number}/{@code List<?>}/{@code Map<?,?>} rather than
 * casts: these documents are deserialised into {@code Map<String,Object>}, where a {@code (Double)}
 * or {@code (List<String>)} cast would erase to {@code Object} and throw deep in the reactor chain
 * (a 500) instead of being reported as a clean anomaly.
 */
@Component
public class DomainInvariantChecker {

    /** Dollar tolerance for the order-total invariant (guards floating-point summation rounding). */
    private static final double TOTAL_EPSILON = 0.001;

    /**
     * {@code Order.totalUsd} must equal the sum of {@code item.unitPriceUsd * item.qty}, every
     * {@code qty >= 1}, and every {@code unitPriceUsd >= 0}.
     */
    public void checkOrder(WorkloadVerificationState state, String op, String container, Map<String, Object> order) {
        long opSeq = state.nextOpSeq();
        String orderId = String.valueOf(order.get("id"));
        if (!(order.get("items") instanceof List<?> items) || items.isEmpty()) {
            record(state, "DOMAIN_INVARIANT_ORDER_SHAPE", op, container, orderId,
                "order has no items[] array", opSeq);
            return;
        }
        double sum = 0.0;
        for (Object raw : items) {
            if (!(raw instanceof Map<?, ?> item)) {
                record(state, "DOMAIN_INVARIANT_ORDER_SHAPE", op, container, orderId,
                    "order item is not an object: " + raw, opSeq);
                return;
            }
            Integer qty = item.get("qty") instanceof Number n ? n.intValue() : null;
            Double price = item.get("unitPriceUsd") instanceof Number n ? n.doubleValue() : null;
            if (qty == null || qty < 1) {
                record(state, "DOMAIN_INVARIANT_ITEM_QTY", op, container, orderId,
                    "order item qty must be >= 1, got " + qty, opSeq);
                return;
            }
            if (price == null || price < 0) {
                record(state, "DOMAIN_INVARIANT_ITEM_PRICE", op, container, orderId,
                    "order item unitPriceUsd must be >= 0, got " + price, opSeq);
                return;
            }
            sum += price * qty;
        }
        Double total = order.get("totalUsd") instanceof Number n ? n.doubleValue() : null;
        if (total == null || Math.abs(total - sum) > TOTAL_EPSILON) {
            record(state, "DOMAIN_INVARIANT_ORDER_TOTAL", op, container, orderId,
                "order totalUsd=" + total + " != sum of line items " + sum, opSeq);
        }
    }

    /** After checkout the customer's cart must be empty. */
    public void checkCartCleared(WorkloadVerificationState state, String op, String container, Map<String, Object> cart) {
        long opSeq = state.nextOpSeq();
        if (cart.get("items") instanceof List<?> items && !items.isEmpty()) {
            record(state, "DOMAIN_INVARIANT_CART_NOT_CLEARED", op, container, String.valueOf(cart.get("id")),
                "cart still has " + items.size() + " item(s) after checkout", opSeq);
        }
    }

    private static void record(WorkloadVerificationState state, String code, String op, String container,
                               String key, String detail, long opSeq) {
        state.record(new WorkloadAnomaly(code, WorkloadAnomaly.SEVERITY_ERROR, op, container, key,
            detail, opSeq, null, null, System.currentTimeMillis()));
    }
}
