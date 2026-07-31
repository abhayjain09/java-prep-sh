package com.interviewprep.orders.patterns.behavioral.templatemethod;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderLine;

import java.util.List;

/**
 * CORRECT — the TEMPLATE METHOD: {@link #process} is {@code final} — the
 * SKELETON (validate, reserve, charge, notify, in that exact order) is
 * defined ONCE, here, and can never be reordered or duplicated by a
 * subclass. Subclasses override only the HOOK methods
 * ({@link #chargeShipping} and {@link #notifyCustomer}) that genuinely vary.
 *
 * {@link #validate} and the stock-reservation loop are written ONCE and
 * shared by every subclass automatically — no copy-paste, and a business
 * rule change to either (e.g. reordering charge-before-reserve) is a
 * ONE-LINE change in this class that every subclass picks up immediately.
 *
 * THIS IS THE "HOLLYWOOD PRINCIPLE" IN ACTION ("don't call us, we'll call
 * you"): subclasses don't call the shared steps themselves — this base
 * class calls their hook overrides, from inside its own fixed algorithm.
 */
public abstract class OrderProcessorTemplate {

    protected final Inventory inventory;

    protected OrderProcessorTemplate(Inventory inventory) {
        this.inventory = inventory;
    }

    /** The template method: fixed skeleton, cannot be overridden or reordered. */
    public final Order process(Customer customer, List<OrderLine> lines) {
        validate(lines);
        reserveStock(lines);
        chargeShipping(lines);
        Order order = new Order("ORD-" + System.nanoTime(), customer);
        lines.forEach(order::addLine);
        notifyCustomer(order);
        return order;
    }

    // Shared step — written once, used by every subclass, cannot drift
    // between "standard" and "express" the way NaiveDuplicatedOrderProcessors did.
    private void validate(List<OrderLine> lines) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Order must have at least one line");
        }
    }

    // Shared step — also written once.
    private void reserveStock(List<OrderLine> lines) {
        for (OrderLine line : lines) {
            inventory.reserve(line.product().sku(), line.quantity());
        }
    }

    /** HOOK — subclasses must define shipping-charge behavior. */
    protected abstract void chargeShipping(List<OrderLine> lines);

    /** HOOK — subclasses must define the customer-facing notification message. */
    protected abstract void notifyCustomer(Order order);
}
