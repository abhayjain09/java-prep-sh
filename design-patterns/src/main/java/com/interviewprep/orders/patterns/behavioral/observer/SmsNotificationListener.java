package com.interviewprep.orders.patterns.behavioral.observer;

import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderStatus;

public class SmsNotificationListener implements OrderStatusListener {
    @Override
    public void onStatusChanged(Order order, OrderStatus oldStatus, OrderStatus newStatus) {
        if (newStatus == OrderStatus.SHIPPED || newStatus == OrderStatus.DELIVERED) {
            // Only SMS for the statuses customers care about getting an
            // immediate text for — a good example of a listener applying
            // its OWN filtering logic without the publisher needing to know.
            System.out.println("SMS to customer " + order.customer().name() + ": order " + order.id()
                    + " " + newStatus);
        }
    }
}
