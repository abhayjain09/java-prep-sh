package com.interviewprep.orders.patterns.behavioral.chainofresponsibility;

public class OrderRejectedException extends RuntimeException {
    public OrderRejectedException(String reason) {
        super(reason);
    }
}
