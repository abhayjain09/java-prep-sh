package com.interviewprep.orders.patterns.structural.bridge;

public class SmsNotificationSender implements NotificationSender {
    @Override
    public void send(String recipient, String message) {
        System.out.println("SMS to " + recipient + ": " + message);
    }
}
