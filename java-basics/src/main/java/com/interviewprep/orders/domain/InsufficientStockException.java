package com.interviewprep.orders.domain;

/**
 * Thrown when Inventory is asked to reserve more stock than is available.
 *
 * WHY UNCHECKED (extends RuntimeException, not Exception): this is a
 * business-rule violation, not a recoverable I/O-style condition every
 * caller up the stack must explicitly plan for. Making it checked would
 * force every method between Inventory.reserve() and the eventual handler
 * (in Module 5, a single Spring @ControllerAdvice mapping this to HTTP 409
 * Conflict) to either declare "throws InsufficientStockException" or
 * catch-and-rethrow — pure boilerplate with no recovery action at those
 * intermediate layers. Unchecked lets it propagate straight to the one
 * place that actually handles it.
 *
 * WHY A CUSTOM EXCEPTION TYPE AT ALL, instead of a generic
 * IllegalStateException: callers (and the future REST layer) need to
 * distinguish "out of stock" from other failures programmatically —
 * catch (InsufficientStockException e) specifically to show the user
 * "only 3 left in stock" rather than a generic error page. A custom type
 * is justified here because the caller has a genuinely different response
 * for this case; don't create a custom exception type for every possible
 * failure reason if callers never need to distinguish between them.
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

    public String sku() {
        return sku;
    }

    public int requested() {
        return requested;
    }

    public int available() {
        return available;
    }
}
