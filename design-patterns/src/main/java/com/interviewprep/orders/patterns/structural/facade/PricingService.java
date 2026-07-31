package com.interviewprep.orders.patterns.structural.facade;

import com.interviewprep.orders.domain.Order;

import java.math.BigDecimal;

/** One of several subsystem services the Facade coordinates. Minimal on purpose. */
public class PricingService {
    public BigDecimal calculateTotal(Order order) {
        return order.totalAmount();
    }
}
