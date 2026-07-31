package com.interviewprep.orders.jvm;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.OrderLine;
import com.interviewprep.orders.domain.Product;
import com.interviewprep.orders.service.OrderService;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A runnable stand-in for the incident described in README section 5:
 * "OrderService.placeOrder is slow under load." This class reproduces the
 * MOST COMMON root cause of that specific complaint — a single coarse lock
 * that every thread must acquire, regardless of which SKU it's actually
 * touching — so lock contention becomes something you can observe (via
 * elapsed wall-clock time here; via JFR's jdk.JavaMonitorEnter /
 * jdk.ThreadPark events on a real JVM) instead of only reading about.
 *
 * BACKGROUND: java-basics/.../Inventory.java documents, deliberately, that
 * reserve() has a check-then-act race condition under concurrent access,
 * left unfixed until Module 3 (Concurrency). Faced with that bug report in
 * production, a team under time pressure very often reaches for the
 * simplest possible fix first: wrap every placeOrder() call in ONE
 * synchronized block, guaranteeing correctness (only one thread mutates
 * Inventory at a time) at the cost of serializing EVERY order placement
 * across the ENTIRE service — two customers ordering completely unrelated
 * products now queue up behind each other for no business reason. This
 * class demonstrates exactly that "fix" and the throughput cost it hides.
 */
public class LockContentionUnderLoadDemo {

    // The naive, overly-coarse lock: one object, guarding EVERY order,
    // regardless of SKU. This is the thing a JFR "Lock Instances" view
    // would identify as the dominant contended monitor in a real capture.
    private static final Object PLACE_ORDER_LOCK = new Object();

    public static void main(String[] args) throws InterruptedException {
        Product product = new Product("SKU-WIDGET", "Widget", new BigDecimal("9.99"));
        Customer customer = new Customer("CUST-LOAD-TEST", "Load Test Customer", "load@example.com");

        Inventory inventory = new Inventory();
        // Plenty of stock: this demo measures LOCK WAIT time, not stockouts.
        inventory.restock(product.sku(), 10_000_000);

        OrderService orderService = new OrderService(inventory);

        int threadCount = 16;
        int ordersPerThread = 5_000;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1); // release all threads simultaneously
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        AtomicInteger placed = new AtomicInteger();

        for (int t = 0; t < threadCount; t++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < ordersPerThread; i++) {
                        // THE BOTTLENECK: every single order placement, on
                        // every thread, funnels through this one monitor.
                        // Under real JFR instrumentation, this line is
                        // where jdk.JavaMonitorEnter events would pile up,
                        // each carrying a "monitor class" of
                        // LockContentionUnderLoadDemo and a duration equal
                        // to however long this thread waited for another
                        // thread already inside the block to finish.
                        synchronized (PLACE_ORDER_LOCK) {
                            orderService.placeOrder(customer, List.of(new OrderLine(product, 1)));
                        }
                        placed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        long start = System.nanoTime();
        startGate.countDown();
        doneGate.await();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        System.out.println("Placed " + placed.get() + " orders across " + threadCount
                + " threads in " + elapsedMs + " ms, every one serialized through a single lock.");
        System.out.println("Remaining stock: " + inventory.stockOf(product.sku()));
        System.out.println();
        System.out.println("ILLUSTRATIVE NOTE: the elapsed time above is real (it's just wall-clock");
        System.out.println("measured in-process) but treat it as a demo, not a benchmark -- a single");
        System.out.println("un-warmed-up run with no fork isolation is exactly the anti-pattern");
        System.out.println("NaiveMicrobenchmarkPitfalls.java (section 6) describes; a valid THROUGHPUT");
        System.out.println("comparison of coarse-lock vs. per-SKU-lock would belong in a JMH benchmark.");
        System.out.println();
        System.out.println("WHAT YOU WOULD DO NEXT IN A REAL INCIDENT (not available in this sandbox):");
        System.out.println("  1. Capture: java -XX:StartFlightRecording=filename=incident.jfr,settings=profile ...");
        System.out.println("  2. Open incident.jfr in JMC -> Java Application -> Lock Instances.");
        System.out.println("     PLACE_ORDER_LOCK would show up as the dominant contended monitor, with");
        System.out.println("     a large summed 'Blocked Time' attributed to this exact call site.");
        System.out.println("  3. Fix: replace the single coarse lock with either");
        System.out.println("       (a) a ConcurrentHashMap<String, Object> of per-SKU lock objects, or");
        System.out.println("       (b) Inventory backed by ConcurrentHashMap.compute() per SKU");
        System.out.println("     (see java-basics EXERCISES.md #5 and Module 3), so unrelated SKUs");
        System.out.println("     never contend with each other at all -- only two threads reserving");
        System.out.println("     the SAME SKU concurrently should ever wait on one another.");
    }
}
