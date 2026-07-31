package com.interviewprep.orders.patterns.behavioral.mediator;

import java.math.BigDecimal;

/**
 * CORRECT — the CONCRETE MEDIATOR: this is the ONE place the checkout
 * workflow ("what happens after stock is reserved, or after pricing is
 * calculated") is written down. Colleagues fire events at it; it decides
 * what to do next, including calling OTHER colleagues.
 *
 * Adding a fourth colleague (e.g. a loyalty-points awarder reacting to
 * {@link #onStockReserved}) means adding ONE field + ONE call, HERE — no
 * existing colleague class needs to change, because none of them call each
 * other directly.
 */
public class CheckoutMediatorImpl implements CheckoutMediator {

    private final NotificationColleague notificationColleague;
    private final PricingColleague pricingColleague;

    public CheckoutMediatorImpl(NotificationColleague notificationColleague) {
        this.notificationColleague = notificationColleague;
        this.pricingColleague = new PricingColleague(this);
    }

    @Override
    public void onStockReserved(String sku, int quantity) {
        // The mediator, not the InventoryColleague, decides that pricing
        // runs next — the workflow lives here, in one readable place.
        System.out.println("Stock reserved for " + sku + " x" + quantity);
    }

    @Override
    public void onStockInsufficient(String sku) {
        notificationColleague.notifyCustomer("Sorry, " + sku + " is out of stock");
    }

    @Override
    public void onPriceCalculated(BigDecimal total) {
        notificationColleague.notifyCustomer("Your total is " + total);
    }

    public PricingColleague pricing() {
        return pricingColleague;
    }
}
