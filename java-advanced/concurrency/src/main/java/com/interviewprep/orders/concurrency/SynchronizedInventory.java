package com.interviewprep.orders.concurrency;

import com.interviewprep.orders.domain.InsufficientStockException;

import java.util.HashMap;
import java.util.Map;

/**
 * FIX #2 for {@code Inventory.reserve()}'s race condition — a single
 * coarse-grained lock guarding the ENTIRE map, using {@code synchronized}.
 *
 * HOW THIS DIFFERS FROM {@link ConcurrentInventory}: this class makes
 * EVERY operation on EVERY sku serialize behind one lock. Two threads
 * reserving completely unrelated SKUs ("SKU-LAPTOP" and "SKU-MOUSE") still
 * block each other here, whereas {@code ConcurrentHashMap.compute()} only
 * blocks threads contending on the SAME key. That's the lock-granularity
 * trade-off this module asks you to understand:
 * <ul>
 *   <li><b>Coarse-grained (this class):</b> trivially easy to reason about —
 *       "only one thread touches inventory state at a time, period." Lower
 *       throughput under contention across many different SKUs, because
 *       unrelated operations needlessly wait on each other.</li>
 *   <li><b>Fine-grained ({@link ConcurrentInventory}, {@link
 *       StripedLockInventory}):</b> higher throughput under multi-SKU
 *       contention, at the cost of more subtle reasoning — and, if you ever
 *       need to lock MULTIPLE keys/locks together for one operation (see
 *       {@link StockTransferService}), fine-grained locking is exactly what
 *       introduces deadlock risk that a single coarse lock never has.</li>
 * </ul>
 *
 * WHY A PRIVATE {@code lock} OBJECT INSTEAD OF {@code synchronized(this)} OR
 * {@code synchronized} METHODS: if this class synchronized on {@code this},
 * any external caller holding a reference to a {@code SynchronizedInventory}
 * could ALSO synchronize on it (e.g. accidentally, or maliciously, via
 * {@code synchronized(myInventory) { ... }} in unrelated code) and either
 * introduce surprise contention or, worse, hold the lock across an
 * unrelated blocking call and stall every legitimate caller. A private,
 * never-exposed lock object means only this class's own methods can ever
 * acquire it — nobody outside can interfere with or be surprised by our
 * locking. This is a standard defensive-locking idiom.
 */
public class SynchronizedInventory implements ReservableInventory {

    private final Map<String, Integer> stockBySku = new HashMap<>();
    private final Object lock = new Object();

    @Override
    public void restock(String sku, int quantity) {
        requirePositive(quantity, "restock quantity");
        synchronized (lock) {
            stockBySku.merge(sku, quantity, Integer::sum);
        }
    }

    @Override
    public void reserve(String sku, int quantity) {
        requirePositive(quantity, "reserve quantity");
        synchronized (lock) {
            // Inside the lock, this is IDENTICAL to Module 1's original,
            // buggy read-check-write — the fix isn't a different algorithm,
            // it's making the whole sequence run under mutual exclusion so
            // no other thread can interleave between the read and the write.
            int available = stockBySku.getOrDefault(sku, 0);
            if (available < quantity) {
                throw new InsufficientStockException(sku, quantity, available);
            }
            stockBySku.put(sku, available - quantity);
        }
    }

    @Override
    public void release(String sku, int quantity) {
        requirePositive(quantity, "release quantity");
        synchronized (lock) {
            stockBySku.merge(sku, quantity, Integer::sum);
        }
    }

    @Override
    public int stockOf(String sku) {
        synchronized (lock) {
            return stockBySku.getOrDefault(sku, 0);
        }
    }

    private static void requirePositive(int quantity, String label) {
        if (quantity <= 0) {
            throw new IllegalArgumentException(label + " must be positive: " + quantity);
        }
    }
}
