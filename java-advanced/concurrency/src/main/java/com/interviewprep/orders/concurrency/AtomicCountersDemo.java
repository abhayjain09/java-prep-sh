package com.interviewprep.orders.concurrency;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link AtomicInteger}, {@link AtomicLong}, and {@link AtomicReference} —
 * lock-free thread safety built on the CPU's compare-and-swap (CAS)
 * instruction rather than a mutex. Internally, every mutating Atomic method
 * (getAndIncrement, updateAndGet, compareAndSet, ...) is a loop: read the
 * current value, compute the new value, attempt a CAS from old-to-new, and
 * retry from the read if another thread's CAS beat us to it. No thread ever
 * blocks another — a thread that "loses" a CAS race simply retries
 * immediately, rather than parking and waiting to be woken up the way a
 * blocked {@code synchronized}/{@code ReentrantLock} thread would. This
 * tends to outperform locking under LOW-to-MODERATE contention; under very
 * HIGH contention, the constant retry-storms can actually make plain
 * locking (or, for pure counters, {@link java.util.concurrent.atomic.LongAdder}
 * — see {@link ConcurrentCollectionsDemo}) win instead.
 *
 * Run standalone with:
 *   java -cp out com.interviewprep.orders.concurrency.AtomicCountersDemo
 */
public final class AtomicCountersDemo {

    public static void main(String[] args) throws InterruptedException {
        run();
    }

    public static void run() throws InterruptedException {
        demonstrateRaceOnPlainLong();
        demonstrateAtomicLong();
        demonstrateAtomicIntegerHighWaterMark();
        demonstrateAtomicReferenceCasLoop();
    }

    /**
     * WRONG: {@code counter[0]++} is read-modify-write over three separate
     * bytecode-level steps (read, add one, write back) — not atomic, and
     * exactly the same race SHAPE as {@code Inventory.reserve()}'s bug,
     * just on a raw counter instead of a stock level.
     */
    private static void demonstrateRaceOnPlainLong() throws InterruptedException {
        // A plain local can't be reassigned from inside a lambda ("effectively
        // final" rule) — wrapping it in a one-element array is the classic
        // (ugly, but standard) workaround when you genuinely need a mutable
        // captured variable instead of a proper concurrency-safe type.
        long[] counter = {0};
        runConcurrently(8, 10_000, () -> counter[0]++);
        long expected = 80_000L;
        System.out.println("WRONG  plain long,  8 threads x 10,000 increments, expected " + expected
                + ", got " + counter[0]
                + (counter[0] == expected ? "  (got lucky this run — still not safe)" : "  <-- lost updates"));
    }

    /** CORRECT: getAndIncrement() is a single atomic CAS-retry-loop operation. */
    private static void demonstrateAtomicLong() throws InterruptedException {
        AtomicLong counter = new AtomicLong(0);
        runConcurrently(8, 10_000, counter::getAndIncrement);
        System.out.println("RIGHT  AtomicLong,  8 threads x 10,000 increments, got " + counter.get()
                + " (always exactly 80000 — this is the same primitive OrderService's orderIdSequence uses)");
    }

    /**
     * A concrete AtomicInteger use case beyond simple counting:
     * {@code accumulateAndGet} to track a lock-free running maximum — here,
     * the peak number of concurrent in-flight "reservation attempts," the
     * kind of number a real metrics/monitoring system tracks continuously.
     */
    private static void demonstrateAtomicIntegerHighWaterMark() throws InterruptedException {
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger peakInFlight = new AtomicInteger(0);
        runConcurrently(16, 50, () -> {
            int current = inFlight.incrementAndGet();
            // accumulateAndGet: CAS-loop "peakInFlight = max(peakInFlight, current)"
            // without ever taking a lock — equivalent to, but lock-free vs:
            //   synchronized (lock) { if (current > peak) peak = current; }
            peakInFlight.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(1); // hold the "slot" briefly so concurrent attempts actually overlap
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
        });
        System.out.println("AtomicInteger high-water mark: peak concurrent in-flight attempts = " + peakInFlight.get());
    }

    /**
     * AtomicReference + a manual compareAndSet retry loop — the general
     * pattern every other Atomic method (and ConcurrentHashMap.compute())
     * is built from underneath. Here: scan several SKUs' stock levels and
     * settle on the one with the lowest stock, without ever taking a lock.
     * <p>
     * HONEST CAVEAT: in this specific demo the stock values are seeded once
     * and never mutated concurrently while the scan runs, so there isn't
     * actually a data race to resolve — every thread will independently
     * compute the same correct answer regardless. The point here is to show
     * the CAS-RETRY MECHANISM itself (get, compute, attempt compareAndSet,
     * loop on failure) in isolation; {@link ConcurrentInventory#reserve}
     * shows the same mechanism doing real work against genuinely
     * concurrently-mutated state.
     */
    private static void demonstrateAtomicReferenceCasLoop() throws InterruptedException {
        ConcurrentInventory inventory = new ConcurrentInventory();
        inventory.restock("SKU-A", 50);
        inventory.restock("SKU-B", 5);
        inventory.restock("SKU-C", 20);
        String[] skus = {"SKU-A", "SKU-B", "SKU-C"};

        AtomicReference<String> lowestStockSku = new AtomicReference<>(null);
        runConcurrently(6, 10, () -> {
            for (String sku : skus) {
                int stock = inventory.stockOf(sku);
                String current;
                do {
                    current = lowestStockSku.get();
                    if (current != null && inventory.stockOf(current) <= stock) {
                        break; // an equal-or-better candidate is already in place
                    }
                    // compareAndSet(expected, newValue) only succeeds if the
                    // reference still holds `current` — if another thread
                    // updated it in between our get() and this call, CAS
                    // fails, we loop, re-read the now-current value, and
                    // re-evaluate rather than blindly overwriting.
                } while (!lowestStockSku.compareAndSet(current, sku));
            }
        });

        String winner = lowestStockSku.get();
        System.out.println("AtomicReference CAS scan result: lowest-stock SKU = " + winner
                + " (stock=" + inventory.stockOf(winner) + ")");
    }

    private static void runConcurrently(int threadCount, int iterationsPerThread, Runnable task)
            throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        task.run();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
