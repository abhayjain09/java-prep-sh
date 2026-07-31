package com.interviewprep.orders.concurrency;

import com.interviewprep.orders.domain.Order;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Stub payment gateway — simulates an external, network-bound charge
 * request. Stands in for the real HTTP call to a payment processor that
 * later modules (Spring, REST clients) would make for real.
 *
 * WHY {@code CompletableFuture.supplyAsync(..., executor)} AND NOT A PLAIN
 * BLOCKING METHOD: a real payment call is I/O — dominated by network
 * latency, not CPU work. Wrapping it in {@code supplyAsync} lets {@link
 * AsyncOrderProcessor} compose it into a pipeline ({@code thenCompose}) and
 * run many charges concurrently across a thread pool, instead of blocking
 * one thread per order sequentially through reserve -> charge -> confirm.
 */
public class PaymentGateway {

    // Orders at or above this amount are treated as "declined" purely to
    // give this demo a deterministic, reproducible failure case to exercise
    // exceptionally()/handle() with. A real gateway's decline reasons
    // (insufficient funds, fraud rules, expired card, issuer outage) are
    // external and non-deterministic from the caller's point of view.
    private static final BigDecimal DECLINE_THRESHOLD = new BigDecimal("5000.00");

    private static final int SIMULATED_LATENCY_MILLIS = 50;

    public CompletableFuture<PaymentResult> chargeAsync(Order order, Executor executor) {
        return CompletableFuture.supplyAsync(() -> charge(order), executor);
    }

    private PaymentResult charge(Order order) {
        simulateNetworkLatency();
        BigDecimal amount = order.totalAmount();
        if (amount.compareTo(DECLINE_THRESHOLD) >= 0) {
            // Thrown from inside supplyAsync's Supplier — CompletableFuture
            // catches this and marks the future "completed exceptionally"
            // rather than propagating it synchronously to this call site.
            throw new PaymentDeclinedException(order.id(), amount);
        }
        return new PaymentResult(UUID.randomUUID().toString(), amount);
    }

    private void simulateNetworkLatency() {
        try {
            Thread.sleep(SIMULATED_LATENCY_MILLIS); // stand-in for a real HTTP round trip
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while charging payment", e);
        }
    }
}
