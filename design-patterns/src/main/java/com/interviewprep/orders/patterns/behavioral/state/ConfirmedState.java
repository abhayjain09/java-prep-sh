package com.interviewprep.orders.patterns.behavioral.state;

import java.math.BigDecimal;

public class ConfirmedState implements OrderState {
    @Override
    public OrderState next() {
        return new ShippedState();
    }

    @Override
    public BigDecimal cancellationFeePercentage() {
        return new BigDecimal("0.05"); // small fee — payment already authorized
    }

    @Override
    public boolean isEditable() {
        return false; // lines are locked once confirmed
    }

    @Override
    public String name() {
        return "CONFIRMED";
    }
}
