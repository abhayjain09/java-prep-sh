package com.interviewprep.orders.concurrency;

import java.math.BigDecimal;

/**
 * Thrown by {@link PaymentGateway} when a simulated charge is declined.
 * Unchecked, for exactly the reason {@code InsufficientStockException} is
 * unchecked in Module 1 — see that class's Javadoc. Here it also
 * demonstrates a business exception thrown from INSIDE a {@code
 * CompletableFuture} pipeline stage, which {@link AsyncOrderProcessor}
 * catches via {@code exceptionally()}/{@code handle()} rather than a normal
 * try/catch — see this module's README for how exceptions thrown inside
 * {@code supplyAsync}/{@code thenApply} get wrapped in a {@link
 * java.util.concurrent.CompletionException} by the time they reach a
 * downstream stage.
 */
public class PaymentDeclinedException extends RuntimeException {

    private final String orderId;
    private final BigDecimal amount;

    public PaymentDeclinedException(String orderId, BigDecimal amount) {
        super("Payment declined for order '%s': amount %s met or exceeded the demo decline threshold"
                .formatted(orderId, amount));
        this.orderId = orderId;
        this.amount = amount;
    }

    public String orderId() {
        return orderId;
    }

    public BigDecimal amount() {
        return amount;
    }
}
