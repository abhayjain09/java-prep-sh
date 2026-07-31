package com.interviewprep.orders.patterns.behavioral.interpreter;

import com.interviewprep.orders.domain.Order;

/**
 * Another TERMINAL expression: "customer's email belongs to a given
 * domain" — a stand-in for a real "customer tier"/"corporate account" rule,
 * kept simple to avoid inventing extra domain fields not present in
 * java-basics' Customer record.
 */
public class CustomerEmailDomainExpression implements DiscountRuleExpression {

    private final String requiredDomainSuffix; // e.g. "@vip-corp.com"

    public CustomerEmailDomainExpression(String requiredDomainSuffix) {
        this.requiredDomainSuffix = requiredDomainSuffix;
    }

    @Override
    public boolean interpret(Order order) {
        return order.customer().email().endsWith(requiredDomainSuffix);
    }
}
