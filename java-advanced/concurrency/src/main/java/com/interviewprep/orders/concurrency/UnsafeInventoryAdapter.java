package com.interviewprep.orders.concurrency;

import com.interviewprep.orders.domain.Inventory;

/**
 * Adapts Module 1's {@code com.interviewprep.orders.domain.Inventory} —
 * unmodified, exactly as it ships in java-basics/ — to the {@link
 * ReservableInventory} interface, so {@link InventoryStressTester} can run
 * the SAME concurrent workload against the original buggy class and against
 * the three fixes in this module, without touching java-basics/ at all
 * (out of scope for this module — see this module's README).
 *
 * This class adds no behavior of its own; it exists purely so the unsafe
 * original can sit next to its fixes behind one interface for a fair,
 * like-for-like stress test comparison.
 */
public final class UnsafeInventoryAdapter implements ReservableInventory {

    private final Inventory delegate;

    public UnsafeInventoryAdapter(Inventory delegate) {
        this.delegate = delegate;
    }

    @Override
    public void restock(String sku, int quantity) {
        delegate.restock(sku, quantity);
    }

    @Override
    public void reserve(String sku, int quantity) {
        delegate.reserve(sku, quantity);
    }

    @Override
    public void release(String sku, int quantity) {
        delegate.release(sku, quantity);
    }

    @Override
    public int stockOf(String sku) {
        return delegate.stockOf(sku);
    }
}
