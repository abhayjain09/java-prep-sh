package com.interviewprep.orders.concurrency;

import com.interviewprep.orders.domain.InsufficientStockException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * FIX #3 — explicit LOCK STRIPING with {@link ReentrantLock}, a middle
 * ground between {@link SynchronizedInventory}'s one-lock-for-everything and
 * {@link ConcurrentInventory}'s effectively one-lock-per-key (via
 * ConcurrentHashMap's internal bins).
 *
 * HOW STRIPING WORKS: instead of one lock (coarse) or one lock per distinct
 * SKU ever seen (which would grow unboundedly — see "common mistake" below),
 * we hash each SKU to one of a FIXED number of lock "stripes"
 * ({@link #STRIPE_COUNT}). Two SKUs that happen to hash to the same stripe
 * will contend with each other (a "false" collision — unrelated but forced
 * to serialize); SKUs on different stripes run fully in parallel. This is
 * exactly the technique {@code java.util.concurrent.ConcurrentHashMap}
 * itself used internally pre-Java 8 (Java 8+ moved to a more granular
 * per-bin CAS/synchronized scheme, which is effectively what {@link
 * ConcurrentInventory} rides on for free).
 *
 * WHY REENTRANTLOCK HERE INSTEAD OF {@code synchronized}: {@code
 * ReentrantLock} offers {@link ReentrantLock#tryLock(long,
 * java.util.concurrent.TimeUnit)} (timeout-bounded acquisition — used by
 * {@link StockTransferService#transferWithTimeout} to AVOID deadlock rather
 * than prevent it via ordering) and {@link ReentrantLock#lockInterruptibly()}
 * (a blocked thread can be interrupted out of waiting), neither of which
 * {@code synchronized} exposes. For plain mutual exclusion with no timeout
 * needs, {@code synchronized} is simpler and JIT-optimizes at least as well —
 * reach for {@code ReentrantLock} specifically when you need one of its
 * extra capabilities, not as a default upgrade.
 *
 * A COMMON MISTAKE THIS CLASS DELIBERATELY AVOIDS: a tempting "simpler"
 * design is {@code ConcurrentHashMap<String, ReentrantLock>.computeIfAbsent}
 * to lazily create one lock PER SKU. That looks finer-grained than striping
 * (zero false contention between different SKUs), but it leaks memory: a
 * lock object is created and kept forever for every distinct SKU the system
 * has EVER seen, with no eviction. A fixed-size stripe array has bounded
 * memory regardless of how many distinct SKUs flow through the system over
 * the application's lifetime — the right trade for a long-running service.
 */
public class StripedLockInventory implements ReservableInventory {

    private static final int DEFAULT_STRIPE_COUNT = 16;

    private final Map<String, Integer> stockBySku = new ConcurrentHashMap<>();
    private final ReentrantLock[] stripes;

    public StripedLockInventory() {
        this(DEFAULT_STRIPE_COUNT);
    }

    public StripedLockInventory(int stripeCount) {
        if (stripeCount <= 0) {
            throw new IllegalArgumentException("stripeCount must be positive: " + stripeCount);
        }
        this.stripes = new ReentrantLock[stripeCount];
        for (int i = 0; i < stripeCount; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    @Override
    public void restock(String sku, int quantity) {
        requirePositive(quantity, "restock quantity");
        ReentrantLock lock = stripeFor(sku);
        lock.lock();
        try {
            stockBySku.merge(sku, quantity, Integer::sum);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void reserve(String sku, int quantity) {
        requirePositive(quantity, "reserve quantity");
        ReentrantLock lock = stripeFor(sku);
        lock.lock();
        try {
            int available = stockBySku.getOrDefault(sku, 0);
            if (available < quantity) {
                throw new InsufficientStockException(sku, quantity, available);
            }
            stockBySku.put(sku, available - quantity);
        } finally {
            // finally block guarantees the lock is released even when the
            // exception above fires mid-critical-section — forgetting this
            // (or forgetting the try entirely) is THE classic ReentrantLock
            // bug: an exception leaves the lock permanently held and every
            // other thread contending for that stripe blocks forever.
            lock.unlock();
        }
    }

    @Override
    public void release(String sku, int quantity) {
        requirePositive(quantity, "release quantity");
        ReentrantLock lock = stripeFor(sku);
        lock.lock();
        try {
            stockBySku.merge(sku, quantity, Integer::sum);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int stockOf(String sku) {
        // Deliberately NOT taking the stripe lock for a plain read: the
        // backing map is a ConcurrentHashMap, so get() is thread-safe and
        // can never see a torn/corrupted value. It might return a value
        // that's one update stale under concurrent modification, which is
        // acceptable for a point-in-time status query — it would NOT be
        // acceptable inside reserve()'s check-then-decrement, which is why
        // that method takes the lock for its whole duration.
        return stockBySku.getOrDefault(sku, 0);
    }

    private ReentrantLock stripeFor(String sku) {
        // Math.floorMod (not %) so a negative hashCode still maps to a
        // valid non-negative array index.
        int index = Math.floorMod(sku.hashCode(), stripes.length);
        return stripes[index];
    }

    private static void requirePositive(int quantity, String label) {
        if (quantity <= 0) {
            throw new IllegalArgumentException(label + " must be positive: " + quantity);
        }
    }
}
