package com.interviewprep.orders.concurrency;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compares {@link Executors#newFixedThreadPool(int)} (platform threads)
 * against {@link Executors#newVirtualThreadPerTaskExecutor()} (Java 21
 * Virtual Threads, finalized under JEP 444) across two very different
 * workloads: many BLOCKING "customer request" tasks (I/O-bound), and a
 * tight computation with no blocking at all (CPU-bound).
 *
 * REQUIRES JAVA 21+ TO COMPILE AND RUN — see this module's README for the
 * exact {@code javac}/{@code java} commands. {@code
 * Executors.newVirtualThreadPerTaskExecutor()} does not exist before
 * Java 21 (it was preview in 19/20 under a different incubating API shape).
 *
 * THE MENTAL MODEL: a platform thread is a thin wrapper around one real OS
 * thread — expensive to create (typically ~1MB default stack, real kernel
 * scheduling overhead), so a pool caps how many run at once. A virtual
 * thread is a JDK-scheduled, cheap (starts around a few hundred bytes,
 * grows as needed), user-mode-ish thread; the JVM "mounts" a runnable
 * virtual thread onto one of a small pool of CARRIER platform threads
 * (sized to CPU cores by default) to actually execute, and "unmounts" it
 * whenever it performs a blocking operation the JDK knows how to
 * cooperate with (I/O, {@code Thread.sleep}, blocking on most {@code
 * java.util.concurrent} locks/queues as of Java 21) — freeing that carrier
 * thread to run a DIFFERENT virtual thread while the first one is blocked.
 * You can therefore have millions of virtual threads "blocked" on I/O
 * simultaneously while only a handful of carrier threads exist.
 *
 * Run standalone with:
 *   java -cp out com.interviewprep.orders.concurrency.VirtualThreadsDemo
 */
public final class VirtualThreadsDemo {

    private static final int IO_TASK_COUNT = 2_000;
    private static final int IO_TASK_SLEEP_MILLIS = 30;
    private static final int FIXED_POOL_SIZE = 100;

    public static void main(String[] args) throws InterruptedException {
        run();
    }

    public static void run() throws InterruptedException {
        runIoBoundComparison();
        System.out.println();
        runCpuBoundComparison();
    }

    /**
     * I/O-BOUND: each "customer request" blocks (here, via {@code
     * Thread.sleep} standing in for a downstream network/DB call) for a
     * fixed duration and does essentially no CPU work. This is exactly the
     * shape of a typical web request handler waiting on a database query or
     * a downstream service call.
     */
    private static void runIoBoundComparison() throws InterruptedException {
        System.out.println("=== I/O-bound workload: " + IO_TASK_COUNT + " tasks x " + IO_TASK_SLEEP_MILLIS
                + "ms simulated blocking I/O each ===");

        Duration platformTime;
        try (ExecutorService platformPool = Executors.newFixedThreadPool(FIXED_POOL_SIZE)) {
            platformTime = timeIoBoundTasks(platformPool, "platform pool (" + FIXED_POOL_SIZE + " threads)");
        }

        Duration virtualTime;
        try (ExecutorService virtualPool = Executors.newVirtualThreadPerTaskExecutor()) {
            virtualTime = timeIoBoundTasks(virtualPool, "virtual-thread-per-task executor");
        }

        System.out.println("Platform pool: " + platformTime.toMillis() + "ms   |   Virtual threads: "
                + virtualTime.toMillis() + "ms");
        System.out.println("Why: with only " + FIXED_POOL_SIZE + " platform threads, " + IO_TASK_COUNT
                + " blocking tasks must run in queued batches of " + FIXED_POOL_SIZE + " ("
                + (int) Math.ceil((double) IO_TASK_COUNT / FIXED_POOL_SIZE) + " batches x "
                + IO_TASK_SLEEP_MILLIS + "ms). Each virtual thread unmounts from its carrier during "
                + "Thread.sleep, so all " + IO_TASK_COUNT + " can be in-flight essentially at once — total time "
                + "tracks closer to ONE sleep duration, not " + IO_TASK_COUNT + "/" + FIXED_POOL_SIZE + " of them.");
    }

    /**
     * CPU-BOUND: each task is a tight loop doing real computation and NEVER
     * blocks. Virtual threads have nothing to unmount for — a CPU-bound
     * virtual thread occupies its carrier platform thread for its entire
     * run, identical to how a plain platform thread would occupy a real OS
     * thread. Both are ultimately bottlenecked by the same number of CPU
     * cores, so virtual threads provide no throughput benefit here — and
     * add a small, usually negligible, scheduling indirection for no gain.
     */
    private static void runCpuBoundComparison() throws InterruptedException {
        int cores = Runtime.getRuntime().availableProcessors();
        int taskCount = cores * 4;
        System.out.println("=== CPU-bound workload: " + taskCount + " tight-loop tasks, no blocking ("
                + cores + " CPU cores detected) ===");

        Duration platformCpuTime;
        try (ExecutorService platformPool = Executors.newFixedThreadPool(cores)) {
            platformCpuTime = timeCpuBoundTasks(platformPool, "platform pool sized to cores", taskCount);
        }

        Duration virtualCpuTime;
        try (ExecutorService virtualPool = Executors.newVirtualThreadPerTaskExecutor()) {
            virtualCpuTime = timeCpuBoundTasks(virtualPool, "virtual threads", taskCount);
        }

        System.out.println("Platform pool: " + platformCpuTime.toMillis() + "ms   |   Virtual threads: "
                + virtualCpuTime.toMillis() + "ms  (expect these to be roughly comparable)");
        System.out.println("Why: this workload never calls anything that unmounts a virtual thread from its "
                + "carrier, so both executors are bottlenecked identically by " + cores + " CPU cores. This is "
                + "the single most important virtual-threads interview point: they fix THREAD-PER-BLOCKING-"
                + "REQUEST scalability, not CPU throughput — don't reach for them to 'speed up' CPU-bound work.");
        System.out.println("(Timing note: this is a rough illustration, not a rigorous benchmark — JIT warmup, "
                + "GC, and machine load add noise. Use JMH, covered in Module 11 (JVM internals), for real numbers.)");
    }

    private static Duration timeIoBoundTasks(ExecutorService executor, String label) throws InterruptedException {
        long start = System.nanoTime();
        CountDownLatch latch = new CountDownLatch(IO_TASK_COUNT);
        for (int i = 0; i < IO_TASK_COUNT; i++) {
            executor.submit(() -> {
                try {
                    Thread.sleep(IO_TASK_SLEEP_MILLIS); // stand-in for a blocking network/DB call
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        System.out.println(label + ": " + elapsed.toMillis() + "ms for " + IO_TASK_COUNT + " tasks");
        return elapsed;
    }

    private static Duration timeCpuBoundTasks(ExecutorService executor, String label, int taskCount)
            throws InterruptedException {
        long start = System.nanoTime();
        // AtomicLong sink: gives the JIT a real reason to keep the loop
        // below instead of proving the result is unused and optimizing the
        // whole task away (a classic "dead code elimination" microbenchmark
        // pitfall — real rigorous benchmarking uses JMH's Blackhole for
        // this; this sink is a cheap approximation good enough for a demo).
        AtomicLong sink = new AtomicLong();
        CountDownLatch latch = new CountDownLatch(taskCount);
        for (int i = 0; i < taskCount; i++) {
            executor.submit(() -> {
                try {
                    long total = 0;
                    for (long n = 0; n < 5_000_000L; n++) {
                        total += n * n;
                    }
                    sink.addAndGet(total);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        System.out.println(label + ": " + elapsed.toMillis() + "ms for " + taskCount
                + " CPU-bound tasks (checksum=" + sink.get() + ", ignore the value — it just prevents dead-code elimination)");
        return elapsed;
    }
}
