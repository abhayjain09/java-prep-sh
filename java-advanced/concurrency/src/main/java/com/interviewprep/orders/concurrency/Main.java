package com.interviewprep.orders.concurrency;

/**
 * Runs every Module 3 demo in sequence against the shared Order/Inventory
 * domain. Read alongside EXPLANATION.md, which walks each class file by
 * file, and README.md, which has the full theory.
 * <p>
 * Each section is wrapped in its own try/catch so that if one demo hits an
 * environment-specific hiccup (timing-sensitive demos like {@link
 * DeadlockDemo} and {@link RaceConditionDemo} depend on real thread
 * scheduling, which varies machine to machine), the rest of the walkthrough
 * still runs and prints its output.
 * <p>
 * Run with (see README.md for the exact two-source-root javac command):
 *   javac -d out $(find java-basics/src/main/java java-advanced/concurrency/src/main/java -name "*.java")
 *   java -cp out com.interviewprep.orders.concurrency.Main
 */
public class Main {

    public static void main(String[] args) {
        section("1. Race condition — reproducing Inventory.reserve()'s bug", RaceConditionDemo::run);
        section("2. Three fixes compared: compute() vs single lock vs striped locks", ConcurrencyFixComparisonDemo::run);
        section("3. Deadlock — two locks, inconsistent order, then fixed", () -> {
            DeadlockDemo.demonstrateDeadlock();
            System.out.println();
            DeadlockDemo.demonstrateFix();
        });
        section("4. ExecutorService + CompletableFuture: batch order pipeline", BatchOrderProcessingDemo::run);
        section("5. Virtual Threads vs platform threads: I/O-bound vs CPU-bound", VirtualThreadsDemo::run);
        section("6. Atomic classes: AtomicInteger / AtomicLong / AtomicReference", AtomicCountersDemo::run);
        section("7. Concurrent collections: ConcurrentHashMap / CopyOnWriteArrayList / BlockingQueue",
                ConcurrentCollectionsDemo::run);
    }

    private static void section(String title, ThrowingRunnable demo) {
        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println(title);
        System.out.println("=".repeat(80));
        try {
            demo.run();
        } catch (Exception e) {
            System.out.println("Demo threw an exception (see stack trace) — continuing with the next one.");
            e.printStackTrace(System.out);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
