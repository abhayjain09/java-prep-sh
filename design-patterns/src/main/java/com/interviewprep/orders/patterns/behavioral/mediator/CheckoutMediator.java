package com.interviewprep.orders.patterns.behavioral.mediator;

/**
 * The MEDIATOR interface: colleagues send events THROUGH this interface
 * instead of calling each other directly. Each colleague only needs to know
 * about the mediator, never about its sibling colleagues.
 */
public interface CheckoutMediator {
    void onStockReserved(String sku, int quantity);
    void onStockInsufficient(String sku);
    void onPriceCalculated(java.math.BigDecimal total);
}
