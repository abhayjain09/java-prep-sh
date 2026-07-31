package com.interviewprep.orders.patterns.behavioral.observer;

import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderStatus;

public class EmailNotificationListener implements OrderStatusListener {
    @Override
    public void onStatusChanged(Order order, OrderStatus oldStatus, OrderStatus newStatus) {
        System.out.println("EMAIL to " + order.customer().email() + ": order " + order.id()
                + " is now " + newStatus);
    }
}
