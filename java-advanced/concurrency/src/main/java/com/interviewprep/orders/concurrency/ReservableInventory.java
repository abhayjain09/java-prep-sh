package com.interviewprep.orders.concurrency;

import com.interviewprep.orders.domain.InsufficientStockException;

/**
 * Common shape shared by every Inventory variant in this module: the
 * original unsafe {@code com.interviewprep.orders.domain.Inventory} (wrapped
 * by {@link UnsafeInventoryAdapter}), {@link ConcurrentInventory}, {@link
 * SynchronizedInventory}, and {@link StripedLockInventory}.
 *
 * WHY THIS INTERFACE EXISTS: Module 1's {@code Inventory} is a concrete
 * class, not an interface — reasonably so, since Module 1 has exactly one
 * implementation and introducing an interface "just in case" would be
 * speculative generality. Module 3 is exactly the case that justifies
 * extracting one: we now have FOUR interchangeable implementations that
 * differ only in their concurrency strategy, and {@link
 * InventoryStressTester} needs to run the identical workload against all of
 * them to make a fair, apples-to-apples comparison. This is the "extract an
 * interface once you have two-plus implementations that need to be used
 * polymorphically" rule of thumb, not a rule "always code to an interface."
 */
public interface ReservableInventory {

    /** Adds {@code quantity} units of {@code sku} to stock. */
    void restock(String sku, int quantity);

    /**
     * Reserves {@code quantity} units of {@code sku}, decrementing stock.
     *
     * @throws InsufficientStockException if fewer than {@code quantity}
     *         units are available. Implementations must guarantee this is
     *         all-or-nothing per call: a failed reservation must leave stock
     *         completely unchanged, never partially decremented.
     */
    void reserve(String sku, int quantity);

    /** Returns previously reserved stock (e.g. on order cancellation). */
    void release(String sku, int quantity);

    /** Current stock level for {@code sku}, or 0 if the SKU is unknown. */
    int stockOf(String sku);
}
