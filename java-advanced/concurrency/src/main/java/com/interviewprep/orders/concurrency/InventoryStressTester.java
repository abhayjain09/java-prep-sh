package com.interviewprep.orders.concurrency;

import com.interviewprep.orders.domain.InsufficientStockException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared concurrent stress-test harness used by {@link RaceConditionDemo}
 * and {@link ConcurrencyFixComparisonDemo} so every {@link
 * ReservableInventory} implementation is put through the IDENTICAL workload
 * — same SKU, same initial stock, same thread count, same iteration count.
 * The only variable that changes between runs is which implementation is
 * under test, which is what makes the comparison meaningful.
 *
 * WHY A "STARTING GATE" LATCH: threads submitted to an ExecutorService don't
 * all start running at the exact same instant — the pool has to schedule
 * them onto OS threads one by one. Without a starting gate, the first
 * few threads might finish their whole workload before the last thread even
 * begins, which would understate contention and make the race far less
 * likely to manifest. Releasing all threads at once via {@link
 * CountDownLatch#countDown()} after they've all called {@code await()}
 * maximizes the actual overlap between threads — exactly the "widen the
 * interleaving window" idea applied to test setup rather than the code
 * under test.
 *
 * IMPORTANT CAVEAT (races are not deterministic): even with a starting
 * gate, a race condition is fundamentally a timing bug. This harness makes
 * it LIKELY to manifest within a handful of runs by using enough threads and
 * enough iterations that the "read stock, then someone else reads it too
 * before either writes" window gets hit — but it is not guaranteed on every
 * single run, especially on a lightly loaded machine or a JVM that happens
 * to schedule things luckily. If {@link RaceConditionDemo} reports
 * "CONSISTENT" once, that is NOT proof the bug is fixed — re-run it, or
 * increase thread/iteration counts, before drawing that conclusion. This is
 * precisely why real-world concurrency bugs are so often shipped to
 * production undetected by a handful of manual test runs, and why stress
 * tests for concurrency bugs are written to run many iterations, often in a
 * loop, in CI.
 */
public final class InventoryStressTester {

    private InventoryStressTester() {
    }

    public static StressTestResult stressTest(ReservableInventory inventory, String sku,
                                               int initialStock, int threadCount,
                                               int reservationsPerThread) throws InterruptedException {
        inventory.restock(sku, initialStock);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startingGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startingGate.await();
                    for (int i = 0; i < reservationsPerThread; i++) {
                        try {
                            inventory.reserve(sku, 1);
                            successCount.incrementAndGet();
                        } catch (InsufficientStockException expectedOnceStockRunsOut) {
                            failureCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }

        long start = System.nanoTime();
        startingGate.countDown();
        finished.await();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        int finalStock = inventory.stockOf(sku);
        return new StressTestResult(initialStock, successCount.get(), failureCount.get(), finalStock, elapsedMillis);
    }

    /**
     * @param initialStock  stock level the SKU was seeded with before the test
     * @param successCount  number of {@code reserve()} calls that succeeded
     * @param failureCount  number of {@code reserve()} calls that threw
     *                      InsufficientStockException (expected once stock hits 0)
     * @param finalStock    stock level read back after every thread finished
     * @param elapsedMillis wall-clock time for all threads to finish (rough
     *                      throughput signal, NOT a rigorous benchmark)
     */
    public record StressTestResult(int initialStock, int successCount, int failureCount,
                                    int finalStock, long elapsedMillis) {

        /**
         * The only correct outcome under concurrency: exactly {@code
         * initialStock} reservations succeed (assuming total attempts >=
         * initialStock, which every demo in this module ensures), and stock
         * never goes negative. Any deviation — successCount above
         * initialStock, or a negative finalStock — is the oversell /
         * lost-update bug made visible.
         */
        public boolean isConsistent() {
            return successCount == initialStock && finalStock == 0;
        }

        public String describe(String label) {
            return "%-55s initial=%-5d success=%-5d failure=%-5d finalStock=%-5d elapsed=%-4dms  => %s".formatted(
                    label, initialStock, successCount, failureCount, finalStock, elapsedMillis,
                    isConsistent() ? "CONSISTENT" : "INCONSISTENT (oversold / lost updates)");
        }
    }
}
