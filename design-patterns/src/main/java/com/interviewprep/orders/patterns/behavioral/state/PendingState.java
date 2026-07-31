package com.interviewprep.orders.patterns.behavioral.state;

import java.math.BigDecimal;

public class PendingState implements OrderState {
    @Override
    public OrderState next() {
        return new ConfirmedState();
    }

    @Override
    public BigDecimal cancellationFeePercentage() {
        return BigDecimal.ZERO; // free to cancel before confirmation
    }

    @Override
    public boolean isEditable() {
        return true;
    }

    @Override
    public String name() {
        return "PENDING";
    }
}
