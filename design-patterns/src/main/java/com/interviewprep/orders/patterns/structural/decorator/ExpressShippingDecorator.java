package com.interviewprep.orders.patterns.structural.decorator;

import com.interviewprep.orders.domain.Order;

import java.math.BigDecimal;

/**
 * CORRECT — another independent surcharge. Composing this WITH
 * {@link GiftWrapDecorator} is now a caller-side decision, not a hardcoded
 * branch:
 * <pre>{@code
 * OrderPricer pricer = new ExpressShippingDecorator(
 *         new GiftWrapDecorator(
 *                 new BaseOrderPricer()));
 * BigDecimal finalPrice = pricer.price(order);
 * }</pre>
 * Reordering the two decorators (gift wrap fee before vs. after the express
 * percentage fee is applied) is just swapping which constructor wraps which
 * — no existing decorator class needs to change either way. A THIRD
 * surcharge (fragile handling) is a THIRD small class, addable without
 * touching this one or GiftWrapDecorator at all — the fix for
 * {@link NaiveOrderPriceCalculator}'s combinatorial growth.
 */
public class ExpressShippingDecorator extends OrderPricerDecorator {

    private static final BigDecimal EXPRESS_SURCHARGE_RATE = new BigDecimal("0.15");

    public ExpressShippingDecorator(OrderPricer wrapped) {
        super(wrapped);
    }

    @Override
    public BigDecimal price(Order order) {
        BigDecimal base = wrapped.price(order);
        return base.add(base.multiply(EXPRESS_SURCHARGE_RATE));
    }
}
