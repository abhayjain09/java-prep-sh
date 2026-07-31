package com.interviewprep.orders.patterns.behavioral.observer;

import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderStatus;

/**
 * The OBSERVER interface: anything that wants to react to an order's status
 * changing implements this, and registers itself with a
 * {@link OrderStatusPublisher} — it never needs to be known about by
 * {@code Order} or by whatever code changes the order's status.
 */
public interface OrderStatusListener {
    void onStatusChanged(Order order, OrderStatus oldStatus, OrderStatus newStatus);
}
