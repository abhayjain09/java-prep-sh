package com.interviewprep.orders.patterns.structural.decorator;

import com.interviewprep.orders.domain.Order;

import java.math.BigDecimal;

/**
 * The COMPONENT interface Decorator wraps: "given an Order, compute its
 * final price." Both the base pricer and every decorator implement this
 * same interface, so decorators can wrap decorators indefinitely and the
 * caller never needs to know how many layers deep the chain is.
 */
public interface OrderPricer {
    BigDecimal price(Order order);
}
