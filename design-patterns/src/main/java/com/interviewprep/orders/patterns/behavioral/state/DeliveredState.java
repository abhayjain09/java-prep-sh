package com.interviewprep.orders.patterns.behavioral.state;

import java.math.BigDecimal;

/** Terminal state — no further "progress" transition, and cancellation no longer applies (would be a return/refund flow instead). */
public class DeliveredState implements OrderState {
    @Override
    public OrderState next() {
        throw new IllegalStateException("DELIVERED is a terminal state — no further progression");
    }

    @Override
    public BigDecimal cancellationFeePercentage() {
        throw new IllegalStateException("Cannot cancel a DELIVERED order — use a return/refund flow instead");
    }

    @Override
    public boolean isEditable() {
        return false;
    }

    @Override
    public String name() {
        return "DELIVERED";
    }
}
