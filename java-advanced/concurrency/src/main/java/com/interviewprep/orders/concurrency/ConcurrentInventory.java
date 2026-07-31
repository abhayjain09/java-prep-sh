package com.interviewprep.orders.concurrency;

import com.interviewprep.orders.domain.InsufficientStockException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FIX #1 for the race condition documented on {@code
 * com.interviewprep.orders.domain.Inventory} (Module 1) — this is the
 * "textbook" fix Module 1's EXERCISES.md exercise 5 asks you to attempt
 * yourself before reading this class.
 *
 * THE BUG BEING FIXED: the original {@code reserve()} does
 * read-check-write as three separate map operations:
 * <pre>
 *     int available = stockOf(sku);              // READ
 *     if (available < quantity) throw ...;        // CHECK
 *     stockBySku.put(sku, available - quantity);  // WRITE
 * </pre>
 * Two threads calling {@code reserve("SKU-X", 1)} concurrently when stock is
 * exactly 1 can BOTH read {@code available == 1} before either has written
 * back — both pass the check, both write {@code stockBySku.put(sku, 0)}, and
 * one unit just got sold twice. See {@code diagrams/race-condition-sequence.md}
 * for the interleaving diagram.
 *
 * THE FIX: {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)}
 * performs the ENTIRE read-check-write as one atomic operation per key. The
 * JDK implementation holds an internal per-bin lock for the whole duration
 * of the remapping function, so no other thread can observe or mutate that
 * key's value while our check-then-decrement logic is running. Crucially,
 * {@code compute()} is specified so that if the remapping function throws,
 * the map's entry for that key is left COMPLETELY UNCHANGED — we get
 * "reserve fully succeeds or has no effect at all" for free, without the
 * manual try/catch-and-undo that {@code OrderService.placeOrder} needs for
 * multi-line orders spanning several SKUs (compute()'s atomicity guarantee
 * is per-key only — see the README's "what this fix does NOT protect
 * against" section).
 *
 * WHY NOT JUST SWAP THE MAP TYPE AND KEEP read-then-put: swapping HashMap
 * for ConcurrentHashMap alone does NOT fix this bug. ConcurrentHashMap
 * guarantees each individual get()/put() call is thread-safe and won't
 * corrupt the map's internal structure, but it does nothing to make a
 * sequence of two calls (get, then put) atomic together. The race is in the
 * gap BETWEEN calls, not inside any single call — this is the single most
 * common misunderstanding of "thread-safe collections" in interviews.
 */
public class ConcurrentInventory implements ReservableInventory {

    private final Map<String, Integer> stockBySku = new ConcurrentHashMap<>();

    @Override
    public void restock(String sku, int quantity) {
        requirePositive(quantity, "restock quantity");
        // merge() is ALSO atomic-per-key on ConcurrentHashMap (unlike on a
        // plain HashMap) — see Module 1's EXPLANATION.md for the general
        // merge() mechanics; the thread-safety upgrade here is purely a
        // consequence of the backing map type, no code change needed.
        stockBySku.merge(sku, quantity, Integer::sum);
    }

    @Override
    public void reserve(String sku, int quantity) {
        requirePositive(quantity, "reserve quantity");
        // The remapping function below runs under ConcurrentHashMap's
        // internal per-bin lock for this key — no other thread can be
        // running a compute()/merge()/put() for the SAME key concurrently.
        // Different keys (different SKUs) are NOT blocked by each other —
        // this is finer-grained than a single lock over the whole map (see
        // SynchronizedInventory for that alternative and its trade-offs).
        stockBySku.compute(sku, (key, currentStock) -> {
            int available = currentStock == null ? 0 : currentStock;
            if (available < quantity) {
                // Throwing HERE, inside the remapping function, is the
                // documented-correct way to abort a compute(): the map is
                // left exactly as it was for this key — no partial write.
                throw new InsufficientStockException(sku, quantity, available);
            }
            return available - quantity;
        });
    }

    @Override
    public void release(String sku, int quantity) {
        requirePositive(quantity, "release quantity");
        stockBySku.merge(sku, quantity, Integer::sum);
    }

    @Override
    public int stockOf(String sku) {
        // A plain get() on ConcurrentHashMap is thread-safe (safe
        // publication guaranteed) without needing compute()/any lock — it's
        // only READ-THEN-WRITE sequences (like reserve()) that need the
        // atomic compound operation.
        return stockBySku.getOrDefault(sku, 0);
    }

    private static void requirePositive(int quantity, String label) {
        if (quantity <= 0) {
            throw new IllegalArgumentException(label + " must be positive: " + quantity);
        }
    }
}
