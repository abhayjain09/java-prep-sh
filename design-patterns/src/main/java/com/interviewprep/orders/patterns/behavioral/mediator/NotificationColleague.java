package com.interviewprep.orders.patterns.behavioral.mediator;

/** A third COLLEAGUE — again, only knows the mediator. */
public class NotificationColleague {
    public void notifyCustomer(String message) {
        System.out.println("Notification: " + message);
    }
}
