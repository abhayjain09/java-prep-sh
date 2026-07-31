package com.interviewprep.orders.concurrency;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Deliberately constructs a two-lock deadlock using {@link
 * StockTransferService#transferUnsafe}, detects it programmatically the same
 * way production monitoring tools do, and then demonstrates both fixes
 * completing successfully under the identical concurrent access pattern.
 *
 * IMPORTANT — HOW THIS DEMO AVOIDS HANGING YOUR JVM FOREVER: a REAL deadlock,
 * by definition, never resolves itself — the two threads that deadlock in
 * {@link #demonstrateDeadlock()} are launched as DAEMON threads and are
 * simply abandoned once we've detected and reported the deadlock. Daemon
 * threads don't prevent the JVM from exiting, so {@code Main} (and this
 * class's own {@code main}) can finish normally even though those two
 * threads are, and remain, permanently stuck. This mirrors reality: once a
 * production thread pair genuinely deadlocks on plain {@code lock()} calls
 * (as opposed to {@code lockInterruptibly()}), there is no in-process way to
 * un-stick them — {@code Thread.interrupt()} does NOT wake a thread blocked
 * in {@link java.util.concurrent.locks.ReentrantLock#lock()} (only {@code
 * lockInterruptibly()} responds to interrupts). The real-world fix at that
 * point is restarting the process; the ENGINEERING fix is never getting into
 * this state, which is what {@link StockTransferService#transferOrdered}
 * and {@link StockTransferService#transferWithTimeout} are for.
 */
public final class DeadlockDemo {

    private static final String SKU = "SKU-LAPTOP";

    public static void main(String[] args) throws Exception {
        demonstrateDeadlock();
        System.out.println();
        demonstrateFix();
    }

    /**
     * Launches two threads that transfer stock in opposite directions
     * between the same two warehouses, using the buggy {@code
     * transferUnsafe}, and uses {@link ThreadMXBean#findDeadlockedThreads()}
     * to detect the resulting deadlock — the same underlying mechanism
     * {@code jstack -l <pid>}, VisualVM, and JDK Mission Control's thread
     * dumps use. Knowing this API exists (and what a thread dump showing
     * "waiting to lock <0x...> which is held by thread ..." means) is a
     * realistic senior-level production-debugging question.
     */
    public static void demonstrateDeadlock() throws InterruptedException {
        System.out.println("=== Constructing a real two-lock deadlock ===");

        Warehouse east = new Warehouse("WH-EAST");
        Warehouse west = new Warehouse("WH-WEST");
        east.restockUnguarded(SKU, 100);
        west.restockUnguarded(SKU, 100);

        StockTransferService transferService = new StockTransferService();

        // holdDelayMillis widens the window between acquiring the first lock
        // and attempting the second, making the deadlock reliably observable
        // instead of depending on lucky (unlucky?) thread scheduling.
        Thread t1 = new Thread(
                () -> transferService.transferUnsafe(east, west, SKU, 10, 300),
                "transfer-east-to-west");
        Thread t2 = new Thread(
                () -> transferService.transferUnsafe(west, east, SKU, 10, 300),
                "transfer-west-to-east");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long deadlineMillis = System.currentTimeMillis() + 5000;
        boolean detected = false;

        while (System.currentTimeMillis() < deadlineMillis) {
            // findDeadlockedThreads() checks for cycles among threads
            // blocked on monitor locks (synchronized) AND ownable
            // synchronizers (java.util.concurrent locks like ReentrantLock,
            // which is what we're using here) — see
            // ThreadMXBean.isSynchronizerUsageSupported().
            long[] deadlockedIds = threadBean.findDeadlockedThreads();
            if (deadlockedIds != null) {
                detected = true;
                ThreadInfo[] infos = threadBean.getThreadInfo(deadlockedIds, true, true);
                System.out.println(">>> DEADLOCK DETECTED via ThreadMXBean (the programmatic equivalent of");
                System.out.println(">>> `jstack -l <pid>` reporting \"Found one Java-level deadlock\") <<<");
                for (ThreadInfo info : infos) {
                    System.out.println("  Thread \"" + info.getThreadName() + "\" (" + info.getThreadState()
                            + ") is waiting to lock " + info.getLockName()
                            + " which is currently held by \"" + info.getLockOwnerName() + "\"");
                }
                break;
            }
            Thread.sleep(200);
        }

        if (!detected) {
            System.out.println("No deadlock detected within 5s in this run — timing-dependent; re-run, or");
            System.out.println("increase holdDelayMillis above to widen the window further.");
        }
        System.out.println("(transfer-east-to-west / transfer-west-to-east are daemon threads — we stop");
        System.out.println("waiting on them here rather than join() them, which would hang forever.)");
    }

    /**
     * Runs the SAME opposite-direction concurrent transfer pattern that
     * deadlocked above, but through {@code transferOrdered} — consistent
     * lock ordering by warehouse id makes the circular wait structurally
     * impossible, so both transfers complete normally.
     */
    public static void demonstrateFix() throws Exception {
        System.out.println("=== Same access pattern, fixed with consistent lock ordering ===");

        Warehouse east = new Warehouse("WH-EAST");
        Warehouse west = new Warehouse("WH-WEST");
        east.restockUnguarded(SKU, 100);
        west.restockUnguarded(SKU, 100);

        StockTransferService transferService = new StockTransferService();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = executor.submit(() -> transferService.transferOrdered(east, west, SKU, 10));
            Future<?> f2 = executor.submit(() -> transferService.transferOrdered(west, east, SKU, 10));

            // get() with a timeout is itself a defensive habit worth calling
            // out: if transferOrdered somehow still had a bug, this would
            // fail fast with a TimeoutException instead of hanging the demo.
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);

            System.out.println("Both transfers completed without deadlock.");
            System.out.println("East stock: " + east.stockOfUnguarded(SKU) + ", West stock: " + west.stockOfUnguarded(SKU)
                    + " (net zero change — 10 moved east->west and 10 moved west->east).");
        } finally {
            executor.shutdown();
        }

        System.out.println();
        System.out.println("=== Alternative fix: tryLock with timeout instead of ordering ===");
        Warehouse north = new Warehouse("WH-NORTH");
        Warehouse south = new Warehouse("WH-SOUTH");
        north.restockUnguarded(SKU, 100);
        south.restockUnguarded(SKU, 100);

        ExecutorService executor2 = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> g1 = executor2.submit(
                    () -> transferService.transferWithTimeout(north, south, SKU, 10, Duration.ofSeconds(2)));
            Future<Boolean> g2 = executor2.submit(
                    () -> transferService.transferWithTimeout(south, north, SKU, 10, Duration.ofSeconds(2)));
            System.out.println("Transfer 1 succeeded: " + g1.get(5, TimeUnit.SECONDS));
            System.out.println("Transfer 2 succeeded: " + g2.get(5, TimeUnit.SECONDS));
            System.out.println("North stock: " + north.stockOfUnguarded(SKU) + ", South stock: " + south.stockOfUnguarded(SKU));
        } finally {
            executor2.shutdown();
        }
    }
}
