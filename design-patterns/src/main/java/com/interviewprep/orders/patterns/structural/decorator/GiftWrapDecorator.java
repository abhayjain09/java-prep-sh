package com.interviewprep.orders.patterns.structural.decorator;

import com.interviewprep.orders.domain.Order;

import java.math.BigDecimal;

/**
 * CORRECT — one independent surcharge as one small class: compute the
 * wrapped price first, then add this decorator's own fee on top. Knows
 * NOTHING about express shipping, tax, or any other decorator that might be
 * stacked above or below it.
 */
public class GiftWrapDecorator extends OrderPricerDecorator {

    private static final BigDecimal GIFT_WRAP_FLAT_FEE = new BigDecimal("5.00");

    public GiftWrapDecorator(OrderPricer wrapped) {
        super(wrapped);
    }

    @Override
    public BigDecimal price(Order order) {
        return wrapped.price(order).add(GIFT_WRAP_FLAT_FEE);
    }
}
