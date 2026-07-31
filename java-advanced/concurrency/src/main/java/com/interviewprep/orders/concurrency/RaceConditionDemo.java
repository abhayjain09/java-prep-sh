package com.interviewprep.orders.concurrency;

import com.interviewprep.orders.domain.Inventory;

/**
 * Reproduces, on demand, the race condition documented on {@code
 * com.interviewprep.orders.domain.Inventory} (Module 1) — plain {@code
 * HashMap}, read-check-write {@code reserve()}, not thread-safe.
 *
 * Run standalone with:
 *   java -cp out com.interviewprep.orders.concurrency.RaceConditionDemo
 */
public final class RaceConditionDemo {

    private static final String SKU = "SKU-LAPTOP";
    private static final int INITIAL_STOCK = 500;
    private static final int THREAD_COUNT = 8;
    private static final int RESERVATIONS_PER_THREAD = 200; // 8 x 200 = 1600 attempts against 500 units

    public static void main(String[] args) throws InterruptedException {
        run();
    }

    public static void run() throws InterruptedException {
        System.out.println("=== Reproducing Inventory.reserve()'s race condition ===");
        System.out.println(THREAD_COUNT + " threads x " + RESERVATIONS_PER_THREAD
                + " reserve(sku, 1) calls each, against " + INITIAL_STOCK + " units of stock.");
        System.out.println("Races are TIMING-DEPENDENT: this is why we hammer it with many threads and");
        System.out.println("many iterations rather than calling reserve() twice and checking once — a single");
        System.out.println("pair of calls is very unlikely to land inside the tiny read-check-write window.");
        System.out.println("A CONSISTENT result below does NOT prove the bug is fixed — it just means this");
        System.out.println("particular run got unlucky (from the bug's perspective) with thread scheduling.");
        System.out.println();

        Inventory unsafeInventory = new Inventory();
        ReservableInventory adapter = new UnsafeInventoryAdapter(unsafeInventory);

        InventoryStressTester.StressTestResult result =
                InventoryStressTester.stressTest(adapter, SKU, INITIAL_STOCK, THREAD_COUNT, RESERVATIONS_PER_THREAD);

        System.out.println(result.describe("plain HashMap-backed Inventory (Module 1, UNFIXED)"));
        System.out.println();

        if (result.isConsistent()) {
            System.out.println("No oversell observed THIS run. The bug is still there — timing just didn't");
            System.out.println("trigger it this time. Re-run this class a few times, or raise THREAD_COUNT /");
            System.out.println("RESERVATIONS_PER_THREAD above, and it will eventually show up. This exact");
            System.out.println("non-determinism is why concurrency bugs slip past manual testing and even");
            System.out.println("past a handful of CI runs, and why they're notorious for surfacing only");
            System.out.println("under production load.");
        } else {
            System.out.println("OVERSOLD. successCount (" + result.successCount() + ") should equal exactly "
                    + INITIAL_STOCK + " and finalStock should be exactly 0 if every reservation had been atomic —");
            System.out.println("at least one of those two numbers is wrong above. This is exactly the documented");
            System.out.println("bug: two or more threads read the same `available` value from stockBySku before");
            System.out.println("ANY of them called put(), so multiple threads independently decided \"yes, there's");
            System.out.println("enough stock\" against data that was already stale by the time they acted on it —");
            System.out.println("a classic lost-update / check-then-act race condition. (Note finalStock can even");
            System.out.println("look deceptively \"plausible\" — e.g. small and positive — because put() always");
            System.out.println("overwrites rather than accumulates: whichever thread's write lands LAST wins,");
            System.out.println("regardless of how many other decrements it silently clobbers.)");
        }
    }
}
