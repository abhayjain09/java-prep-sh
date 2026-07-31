package com.interviewprep.orders.concurrency;

/**
 * Runs the IDENTICAL stress test from {@link RaceConditionDemo} against the
 * three fixes in this module, so correctness AND rough throughput can be
 * compared side by side.
 *
 * Run standalone with:
 *   java -cp out com.interviewprep.orders.concurrency.ConcurrencyFixComparisonDemo
 */
public final class ConcurrencyFixComparisonDemo {

    private static final String SKU = "SKU-LAPTOP";
    private static final int INITIAL_STOCK = 500;
    private static final int THREAD_COUNT = 8;
    private static final int RESERVATIONS_PER_THREAD = 200;

    public static void main(String[] args) throws InterruptedException {
        run();
    }

    public static void run() throws InterruptedException {
        System.out.println("=== Same stress test as RaceConditionDemo, three fixes compared ===");
        System.out.println();

        printResult(new ConcurrentInventory(),
                "ConcurrentHashMap.compute() (ConcurrentInventory)");
        printResult(new SynchronizedInventory(),
                "single coarse-grained lock (SynchronizedInventory)");
        printResult(new StripedLockInventory(16),
                "16-way striped per-SKU locks (StripedLockInventory)");

        System.out.println();
        System.out.println("All three are CORRECT here (no oversell) — under this single-SKU workload they");
        System.out.println("differ mainly in code complexity, not throughput, because every thread contends");
        System.out.println("on the SAME sku regardless of strategy. The throughput advantage that striping and");
        System.out.println("compute() actually provide only shows up under a MULTI-SKU workload, where");
        System.out.println("SynchronizedInventory forces unrelated SKUs to serialize behind its one lock and");
        System.out.println("the other two let them run fully in parallel. See README.md 'Lock granularity'");
        System.out.println("for the full trade-off discussion, and treat the elapsed-time numbers above as a");
        System.out.println("rough illustration, not a rigorous benchmark — JIT warmup, GC pauses, and machine");
        System.out.println("load all add noise; use JMH (Module 11 — JVM internals) for real measurements.");
    }

    private static void printResult(ReservableInventory inventory, String label) throws InterruptedException {
        InventoryStressTester.StressTestResult result =
                InventoryStressTester.stressTest(inventory, SKU, INITIAL_STOCK, THREAD_COUNT, RESERVATIONS_PER_THREAD);
        System.out.println(result.describe(label));
    }
}
