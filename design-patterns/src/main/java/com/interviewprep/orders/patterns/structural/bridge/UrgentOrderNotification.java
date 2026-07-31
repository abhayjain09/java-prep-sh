package com.interviewprep.orders.patterns.structural.bridge;

/**
 * A REFINED ABSTRACTION: a different "kind" of notification (urgent framing,
 * could also override notifyCustomer to retry sends), still fully decoupled
 * from delivery channel — works with any NotificationSender passed in,
 * including channels that don't exist yet.
 */
public class UrgentOrderNotification extends OrderNotification {

    public UrgentOrderNotification(NotificationSender sender) {
        super(sender);
    }

    @Override
    public void notifyCustomer(String recipient, String orderId) {
        sender.send(recipient, "[URGENT] Action needed on order " + orderId + "!");
    }
}
