package com.interviewprep.orders.patterns.behavioral.strategy;

import java.math.BigDecimal;

public class PercentageDiscountStrategy implements DiscountStrategy {

    private final BigDecimal percentage; // e.g. 0.10 for 10% off

    public PercentageDiscountStrategy(BigDecimal percentage) {
        this.percentage = percentage;
    }

    @Override
    public BigDecimal apply(BigDecimal amount) {
        return amount.subtract(amount.multiply(percentage));
    }
}
