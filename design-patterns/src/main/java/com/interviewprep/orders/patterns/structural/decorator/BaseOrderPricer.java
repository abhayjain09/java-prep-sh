package com.interviewprep.orders.patterns.structural.decorator;

import com.interviewprep.orders.domain.Order;

import java.math.BigDecimal;

/** The CONCRETE COMPONENT: plain order total, no surcharges. */
public class BaseOrderPricer implements OrderPricer {
    @Override
    public BigDecimal price(Order order) {
        return order.totalAmount();
    }
}
