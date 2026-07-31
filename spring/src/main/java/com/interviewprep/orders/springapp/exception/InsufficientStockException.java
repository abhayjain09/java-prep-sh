package com.interviewprep.orders.springapp.exception;

/**
 * Same role as {@code java-basics/.../domain/InsufficientStockException.java}
 * — thrown when a stock decrement is requested for more than is on hand.
 *
 * WHY STILL UNCHECKED (extends RuntimeException): identical reasoning to
 * java-basics, now made concrete rather than "previewed" — this module IS
 * the "Module 5" that java-basics' Javadoc pointed forward to. Exactly one
 * place handles this exception: {@code GlobalExceptionHandler}
 * (@RestControllerAdvice), which maps it to HTTP 409 Conflict. Making it
 * checked would force every layer between {@code Product.decrementStock()}
 * and that handler (the service layer, potentially nested transactional
 * methods) to declare `throws InsufficientStockException` for zero benefit
 * — none of those layers have a meaningful recovery action beyond
 * propagating it upward.
 */
public class InsufficientStockException extends RuntimeException {

    private final String sku;
    private final int requested;
    private final int available;

    public InsufficientStockException(String sku, int requested, int available) {
        super("Insufficient stock for sku '%s': requested %d, available %d"
                .formatted(sku, requested, available));
        this.sku = sku;
        this.requested = requested;
        this.available = available;
    }

    public String getSku() {
        return sku;
    }

    public int getRequested() {
        return requested;
    }

    public int getAvailable() {
        return available;
    }
}
