package com.interviewprep.orders.patterns.structural.facade;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.InsufficientStockException;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderLine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * CORRECT — the Facade: one simple entry point ({@link #checkout}) hides the
 * coordination of three subsystems (Inventory, PricingService,
 * NotificationService) behind a single method call.
 *
 * WHAT CALLERS GAIN: a REST controller (Module 5), an admin tool, and a
 * batch job can all call {@code checkoutFacade.checkout(...)} and get
 * IDENTICAL, correct sequencing and rollback behavior — because there is
 * exactly one implementation of that sequencing, not one per caller.
 *
 * IMPORTANT DISTINCTION FROM ADAPTER: Facade does NOT translate an
 * incompatible interface into a compatible one (that's Adapter, see
 * gof/structural/adapter) — every subsystem here already has a perfectly
 * usable API. Facade's job is purely to SIMPLIFY a multi-step interaction
 * for one common use case, while still leaving the subsystems' full APIs
 * directly accessible for callers with more specialized needs (e.g. an
 * admin tool that legitimately needs fine-grained control over just the
 * reservation step, bypassing pricing/notification, can still use
 * {@code Inventory} directly — the Facade is a convenience layer, not a
 * lockdown).
 */
public class CheckoutFacade {

    private final Inventory inventory;
    private final PricingService pricingService;
    private final NotificationService notificationService;

    public CheckoutFacade(Inventory inventory, PricingService pricingService,
                           NotificationService notificationService) {
        this.inventory = inventory;
        this.pricingService = pricingService;
        this.notificationService = notificationService;
    }

    /**
     * The one method every caller needs: reserve stock (all-or-nothing),
     * build the order, price it, and notify the customer — in the one
     * correct order, every time.
     */
    public Order checkout(Customer customer, List<OrderLine> lines, String orderId) {
        reserveAllOrNothing(lines);

        Order order = new Order(orderId, customer);
        lines.forEach(order::addLine);

        pricingService.calculateTotal(order); // computed here; a real system would attach it to the order/receipt
        notificationService.sendOrderConfirmation(order);

        return order;
    }

    private void reserveAllOrNothing(List<OrderLine> lines) {
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
    }
}
