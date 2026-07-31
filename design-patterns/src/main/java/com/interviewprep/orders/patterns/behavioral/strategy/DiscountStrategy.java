package com.interviewprep.orders.patterns.behavioral.strategy;

import java.math.BigDecimal;

/**
 * The STRATEGY interface: one algorithm family (how to discount an amount),
 * many interchangeable implementations. Callers hold a reference to this
 * interface and never need to know which concrete discount rule is plugged
 * in.
 */
public interface DiscountStrategy {
    BigDecimal apply(BigDecimal amount);
}
