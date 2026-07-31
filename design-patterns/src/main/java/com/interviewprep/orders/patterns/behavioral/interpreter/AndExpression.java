package com.interviewprep.orders.patterns.behavioral.interpreter;

import com.interviewprep.orders.domain.Order;

/** NON-TERMINAL expression: combines two sub-expressions with AND. */
public class AndExpression implements DiscountRuleExpression {

    private final DiscountRuleExpression left;
    private final DiscountRuleExpression right;

    public AndExpression(DiscountRuleExpression left, DiscountRuleExpression right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean interpret(Order order) {
        return left.interpret(order) && right.interpret(order);
    }
}
