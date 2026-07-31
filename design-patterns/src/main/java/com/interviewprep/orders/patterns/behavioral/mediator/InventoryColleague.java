package com.interviewprep.orders.patterns.behavioral.mediator;

import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.InsufficientStockException;

/**
 * A COLLEAGUE: knows only about the {@link CheckoutMediator}, never about
 * {@code PricingColleague} or {@code NotificationColleague} directly — it
 * reports events (stock reserved / insufficient) to the mediator, and the
 * mediator decides what happens next.
 */
public class InventoryColleague {

    private final Inventory inventory;
    private final CheckoutMediator mediator;

    public InventoryColleague(Inventory inventory, CheckoutMediator mediator) {
        this.inventory = inventory;
        this.mediator = mediator;
    }

    public void tryReserve(String sku, int quantity) {
        try {
            inventory.reserve(sku, quantity);
            mediator.onStockReserved(sku, quantity); // reports to the mediator, not to a sibling colleague
        } catch (InsufficientStockException e) {
            mediator.onStockInsufficient(sku);
        }
    }
}
