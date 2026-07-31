package com.interviewprep.orders.patterns.structural.facade;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.InsufficientStockException;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderLine;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * WRONG — every caller (a REST controller, an admin backoffice tool, a batch
 * reorder job) that needs to check out an order has to know, IN THE RIGHT
 * ORDER, about THREE separate subsystems: {@link Inventory} (reserve stock,
 * roll back on partial failure), {@link PricingService} (compute the total),
 * and {@link NotificationService} (confirm to the customer) — and must get
 * the sequencing and error handling right EVERY TIME it's written.
 *
 * WHY THIS IS A PROBLEM:
 * 1. KNOWLEDGE DUPLICATION: the reservation-with-rollback dance below is
 *    copy-pasted from OrderService.placeOrder() in java-basics — every new
 *    caller either duplicates it again or (more realistically, in a rushed
 *    codebase) does a SIMPLER, WRONG version that skips the rollback,
 *    silently leaking reserved stock on failure.
 * 2. HIGH COUPLING (see GRASP.md — Low Coupling principle): this class
 *    depends directly on THREE subsystems' concrete APIs. A change to any
 *    one of them (e.g. PricingService gaining a required tax-jurisdiction
 *    parameter) forces a change here AND in every other caller that
 *    orchestrates checkout by hand.
 * 3. HARD TO KEEP CONSISTENT: nothing stops a second caller from calling
 *    these three subsystems in a DIFFERENT order (e.g. sending the
 *    notification before confirming stock was actually reserved) —
 *    producing inconsistent behavior across the codebase for what should be
 *    one well-defined business operation.
 *
 * See {@link CheckoutFacade} for the fix: ONE method, ONE place the
 * subsystem sequencing and error handling live.
 */
public class NaiveCheckoutClient {

    private final Inventory inventory;
    private final PricingService pricingService = new PricingService();
    private final NotificationService notificationService = new NotificationService();

    public NaiveCheckoutClient(Inventory inventory) {
        this.inventory = inventory;
    }

    public Order checkout(Customer customer, List<OrderLine> lines, String orderId) {
        // Subsystem 1: Inventory, WITH manual rollback duplicated from
        // OrderService.placeOrder (java-basics) — every caller doing this
        // by hand is a maintenance and correctness liability.
        Deque<OrderLine> reserved = new ArrayDeque<>();
        try {
            for (OrderLine line : lines) {
                inventory.reserve(line.product().sku(), line.quantity());
                reserved.push(line);
            }
        } catch (InsufficientStockException e) {
            for (OrderLine line : reserved) {
                inventory.release(line.product().sku(), line.quantity());
            }
            throw e;
        }

        Order order = new Order(orderId, customer);
        lines.forEach(order::addLine);

        // Subsystem 2: Pricing.
        BigDecimal total = pricingService.calculateTotal(order);
        System.out.println("Computed total: " + total);

        // Subsystem 3: Notification. A caller in a hurry might forget this
        // step entirely, or call it before stock is confirmed reserved —
        // nothing enforces the correct sequence except programmer discipline.
        notificationService.sendOrderConfirmation(order);

        return order;
    }
}
