package com.interviewprep.orders.patterns.behavioral.strategy;

import java.math.BigDecimal;

public class FlatAmountDiscountStrategy implements DiscountStrategy {

    private final BigDecimal flatAmount;

    public FlatAmountDiscountStrategy(BigDecimal flatAmount) {
        this.flatAmount = flatAmount;
    }

    @Override
    public BigDecimal apply(BigDecimal amount) {
        return amount.subtract(flatAmount).max(BigDecimal.ZERO);
    }
}
