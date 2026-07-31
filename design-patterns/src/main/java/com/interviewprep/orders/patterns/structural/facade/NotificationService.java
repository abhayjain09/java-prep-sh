package com.interviewprep.orders.patterns.structural.facade;

import com.interviewprep.orders.domain.Order;

/** Another subsystem service the Facade coordinates. Minimal on purpose. */
public class NotificationService {
    public void sendOrderConfirmation(Order order) {
        System.out.println("Notification sent: order " + order.id() + " confirmed for "
                + order.customer().name());
    }
}
