package com.interviewprep.orders.patterns.behavioral.interpreter;

import com.interviewprep.orders.domain.Order;

/**
 * The ABSTRACT EXPRESSION interface: every node in a discount-eligibility
 * rule "tree" — whether a leaf condition (spend threshold, customer tier)
 * or a combinator (AND/OR of other expressions) — implements this same
 * {@code interpret(Order)} method. This uniformity is what lets expressions
 * nest arbitrarily deep and be evaluated with one polymorphic call.
 */
public interface DiscountRuleExpression {
    boolean interpret(Order order);
}
