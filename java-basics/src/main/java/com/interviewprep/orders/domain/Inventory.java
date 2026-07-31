package com.interviewprep.orders.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks stock levels per product SKU.
 *
 * WHY ENCAPSULATION MATTERS HERE (the core lesson of this class): stockBySku
 * is private and NEVER exposed directly. Every mutation goes through
 * reserve()/release()/restock(), each of which enforces the one invariant
 * that matters — stock can never go negative. If callers could reach the
 * backing Map directly (e.g. via a public getter returning it as-is), that
 * invariant could be violated from any call site, and bugs would have to be
 * hunted across the whole codebase instead of in one class.
 *
 * WHY Map<String, Integer> AND NOT A RAW TYPE: generics make it a compile
 * error to put anything but a String key / Integer value in this map — see
 * java-basics/README.md section 3 for the raw-type version of this class
 * and why it's wrong.
 *
 * THREAD SAFETY (deliberately out of scope here): HashMap is NOT thread-safe.
 * Two threads calling reserve() concurrently on the same SKU can both read
 * the same stock level before either writes back, over-selling the same
 * unit twice (a classic race condition / lost-update bug). This class is
 * intentionally left non-thread-safe in Module 1 — Module 3 (Concurrency)
 * revisits this exact class and fixes it with either synchronization,
 * ConcurrentHashMap.compute(), or an AtomicInteger per SKU, and explains
 * the trade-offs between those options.
 */
public class Inventory {

    private final Map<String, Integer> stockBySku = new HashMap<>();

    public void restock(String sku, int quantity) {
        requirePositive(quantity, "restock quantity");
        stockBySku.merge(sku, quantity, Integer::sum);
    }

    /**
     * Reserves {@code quantity} units of {@code sku}, decrementing stock.
     * Throws InsufficientStockException (unchecked — see that class's
     * Javadoc) if not enough stock is available, and does NOT partially
     * decrement in that case — the reservation either fully succeeds or
     * has no effect at all.
     */
    public void reserve(String sku, int quantity) {
        requirePositive(quantity, "reserve quantity");
        int available = stockOf(sku);
        if (available < quantity) {
            throw new InsufficientStockException(sku, quantity, available);
        }
        stockBySku.put(sku, available - quantity);
    }

    /** Returns previously reserved stock (e.g. on order cancellation). */
    public void release(String sku, int quantity) {
        requirePositive(quantity, "release quantity");
        stockBySku.merge(sku, quantity, Integer::sum);
    }

    public int stockOf(String sku) {
        // getOrDefault avoids a null check the caller would otherwise need —
        // an unknown SKU simply has zero stock rather than being a special case.
        return stockBySku.getOrDefault(sku, 0);
    }

    private static void requirePositive(int quantity, String label) {
        if (quantity <= 0) {
            throw new IllegalArgumentException(label + " must be positive: " + quantity);
        }
    }
}
