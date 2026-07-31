package com.interviewprep.orders.patterns.structural.bridge;

public class EmailNotificationSender implements NotificationSender {
    @Override
    public void send(String recipient, String message) {
        System.out.println("EMAIL to " + recipient + ": " + message);
    }
}
