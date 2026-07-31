package com.interviewprep.orders.patterns.behavioral.strategy;

import java.math.BigDecimal;

public class NoDiscountStrategy implements DiscountStrategy {
    @Override
    public BigDecimal apply(BigDecimal amount) {
        return amount;
    }
}
