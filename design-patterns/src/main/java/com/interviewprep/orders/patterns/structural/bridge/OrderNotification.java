package com.interviewprep.orders.patterns.structural.bridge;

/**
 * CORRECT — the ABSTRACTION side of the Bridge: "what kind of notification"
 * (here, a standard one), decoupled from "how it's delivered" by HOLDING a
 * {@link NotificationSender} rather than extending a channel-specific class.
 *
 * Adding a third channel (push notifications) means writing ONE new
 * {@code PushNotificationSender implements NotificationSender} — zero new
 * classes needed on the abstraction side, because {@link OrderNotification}
 * and {@link UrgentOrderNotification} already work with ANY sender.
 * Symmetrically, adding a third notification kind means one new subclass of
 * this class — it automatically works with every existing (and future)
 * sender. The two hierarchies vary completely independently, which is
 * exactly what eliminates the class explosion in
 * {@link NaiveNotificationClassExplosion}.
 */
public class OrderNotification {

    protected final NotificationSender sender; // the "bridge" to the implementor hierarchy

    public OrderNotification(NotificationSender sender) {
        this.sender = sender;
    }

    public void notifyCustomer(String recipient, String orderId) {
        sender.send(recipient, "Your order " + orderId + " was placed.");
    }
}
