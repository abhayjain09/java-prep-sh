package com.interviewprep.orders.patterns.structural.decorator;

import com.interviewprep.orders.domain.Order;

import java.math.BigDecimal;

/**
 * Abstract base for decorators: holds the WRAPPED pricer and implements
 * {@link OrderPricer} itself — this dual role (both "is-an" OrderPricer and
 * "has-a" OrderPricer) is the mechanical definition of Decorator, and is
 * what lets decorators be nested arbitrarily deep.
 */
public abstract class OrderPricerDecorator implements OrderPricer {
    protected final OrderPricer wrapped;

    protected OrderPricerDecorator(OrderPricer wrapped) {
        this.wrapped = wrapped;
    }
}
