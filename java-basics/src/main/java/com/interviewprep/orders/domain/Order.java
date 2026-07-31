package com.interviewprep.orders.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * An order placed by a Customer, composed of OrderLines, moving through the
 * OrderStatus lifecycle.
 *
 * WHY THIS IS A CLASS, NOT A RECORD (unlike Customer/Product/OrderLine):
 * an Order has genuine identity (two orders with identical lines placed by
 * the same customer are still different orders) and mutable state that
 * changes over its lifetime (lines can be added before checkout, status
 * changes as it's fulfilled). Records are for immutable value objects with
 * no identity beyond their fields — Order is the opposite of that, so a
 * plain class with encapsulated mutable state is the right tool.
 */
public class Order {

    private final String id;
    private final Customer customer;
    private final List<OrderLine> lines = new ArrayList<>();
    private OrderStatus status = OrderStatus.PENDING;

    public Order(String id, Customer customer) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Order id must not be blank");
        }
        this.id = id;
        this.customer = customer;
    }

    public void addLine(OrderLine line) {
        lines.add(line);
    }

    /**
     * WHY A DEFENSIVE COPY (List.copyOf), NOT "return lines;": returning the
     * live internal list would let any caller mutate this Order's state
     * from the outside (order.getLines().clear() would silently empty the
     * order) with no validation and no way for Order to notice. Returning
     * an immutable copy means the only way to change an order's lines is
     * through addLine(), which is the one place that could later enforce
     * rules (e.g. "can't add lines to a SHIPPED order") without an
     * exhaustive audit of every call site that touches the list.
     */
    public List<OrderLine> getLines() {
        return List.copyOf(lines);
    }

    public BigDecimal totalAmount() {
        return lines.stream()
                .map(OrderLine::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Moves this order to {@code next}, delegating the legality check to
     * OrderStatus.canTransitionTo() so the transition rules live in exactly
     * one place (see OrderStatus's Javadoc).
     */
    public void transitionTo(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Cannot transition order %s from %s to %s".formatted(id, status, next));
        }
        status = next;
    }

    public String id() {
        return id;
    }

    public Customer customer() {
        return customer;
    }

    public OrderStatus status() {
        return status;
    }

    @Override
    public String toString() {
        return "Order[id=%s, customer=%s, status=%s, total=%s, lines=%d]"
                .formatted(id, customer.name(), status, totalAmount(), lines.size());
    }
}
