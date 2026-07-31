package com.interviewprep.orders.patterns.behavioral.state;

import java.math.BigDecimal;

/** Terminal state. */
public class CancelledState implements OrderState {
    @Override
    public OrderState next() {
        throw new IllegalStateException("CANCELLED is a terminal state — no further progression");
    }

    @Override
    public BigDecimal cancellationFeePercentage() {
        throw new IllegalStateException("Order is already CANCELLED");
    }

    @Override
    public boolean isEditable() {
        return false;
    }

    @Override
    public String name() {
        return "CANCELLED";
    }
}
