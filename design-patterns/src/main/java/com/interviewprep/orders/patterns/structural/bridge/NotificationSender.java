package com.interviewprep.orders.patterns.structural.bridge;

/**
 * The IMPLEMENTOR hierarchy's interface: "how" a message is physically
 * delivered. Varies independently of "what kind of order notification"
 * (see {@link OrderNotification}) is being sent — that independence is the
 * entire point of Bridge.
 */
public interface NotificationSender {
    void send(String recipient, String message);
}
