package com.interviewprep.orders.concurrency;

import com.interviewprep.orders.domain.Order;

/**
 * Result of {@link AsyncOrderProcessor#processOrderAsync}, normalizing both
 * the success and failure paths into a single return type instead of the
 * caller having to catch exceptions out of a {@code CompletableFuture}
 * chain.
 *
 * @param order   the created Order, or {@code null} if stock reservation
 *                itself failed (the order was never created at all — see
 *                {@link AsyncOrderProcessor})
 * @param success whether the order was fully reserved AND paid for
 * @param detail  human-readable explanation, useful for logging/demo output
 */
public record OrderOutcome(Order order, boolean success, String detail) {
}
