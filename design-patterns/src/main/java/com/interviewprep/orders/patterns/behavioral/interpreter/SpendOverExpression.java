package com.interviewprep.orders.patterns.behavioral.interpreter;

import com.interviewprep.orders.domain.Order;

import java.math.BigDecimal;

/** TERMINAL expression (a leaf — no sub-expressions): "order total exceeds a threshold." */
public class SpendOverExpression implements DiscountRuleExpression {

    private final BigDecimal threshold;

    public SpendOverExpression(BigDecimal threshold) {
        this.threshold = threshold;
    }

    @Override
    public boolean interpret(Order order) {
        return order.totalAmount().compareTo(threshold) > 0;
    }
}
