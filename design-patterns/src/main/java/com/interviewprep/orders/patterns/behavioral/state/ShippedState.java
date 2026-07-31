package com.interviewprep.orders.patterns.behavioral.state;

import java.math.BigDecimal;

public class ShippedState implements OrderState {
    @Override
    public OrderState next() {
        return new DeliveredState();
    }

    @Override
    public BigDecimal cancellationFeePercentage() {
        return new BigDecimal("0.20"); // restocking/return-shipping cost recovery
    }

    @Override
    public boolean isEditable() {
        return false;
    }

    @Override
    public String name() {
        return "SHIPPED";
    }
}
