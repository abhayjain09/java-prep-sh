package com.interviewprep.orders.patterns.behavioral.observer;

import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderStatus;

public class AuditLogListener implements OrderStatusListener {
    @Override
    public void onStatusChanged(Order order, OrderStatus oldStatus, OrderStatus newStatus) {
        System.out.println("AUDIT: order " + order.id() + " transitioned " + oldStatus + " -> " + newStatus
                + " at " + java.time.Instant.now());
    }
}
