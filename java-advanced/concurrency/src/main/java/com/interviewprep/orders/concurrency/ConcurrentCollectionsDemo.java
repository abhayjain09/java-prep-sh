package com.interviewprep.orders.concurrency;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.InsufficientStockException;
import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderLine;
import com.interviewprep.orders.domain.Product;
import com.interviewprep.orders.service.OrderService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

/**
 * Three concurrent collections, three distinct, concrete use cases:
 * {@link ConcurrentHashMap} for high-fan-in counters, {@link
 * CopyOnWriteArrayList} for a read-heavy audit log, and {@link
 * BlockingQueue} as a bounded producer/consumer order-intake pipeline.
 *
 * Run standalone with:
 *   java -cp out com.interviewprep.orders.concurrency.ConcurrentCollectionsDemo
 */
public final class ConcurrentCollectionsDemo {

    public static void main(String[] args) throws InterruptedException {
        run();
    }

    public static void run() throws InterruptedException {
        demonstrateConcurrentHashMapCounters();
        System.out.println();
        demonstrateCopyOnWriteAuditLog();
        System.out.println();
        demonstrateBlockingQueuePipeline();
    }

    /**
     * USE CASE: per-SKU reservation-attempt counters written by many
     * threads. {@code computeIfAbsent} is atomic-per-key on
     * ConcurrentHashMap, so even with 8 threads racing to be first to touch
     * a brand-new SKU, exactly one {@link LongAdder} is ever created for it.
     * <p>
     * WHY {@code LongAdder} INSTEAD OF {@code AtomicLong} HERE: under HIGH
     * write contention from many threads all incrementing the SAME counter,
     * a single AtomicLong becomes a hot spot — every thread's CAS attempt
     * contends with every other thread's. LongAdder internally stripes the
     * count across multiple internal cells (one thread's increment mostly
     * hits a different cell than another's), trading a slower {@code sum()}
     * (which must add up every cell — fine, since reads are rare here: we
     * only call it once at the end to print totals) for much better write
     * throughput. Prefer AtomicLong when you need compareAndSet semantics
     * or reads are frequent relative to writes; prefer LongAdder for
     * pure, high-frequency counting (metrics, request counts) under real
     * contention.
     */
    private static void demonstrateConcurrentHashMapCounters() throws InterruptedException {
        ConcurrentHashMap<String, LongAdder> attemptsBySku = new ConcurrentHashMap<>();
        String[] skus = {"SKU-LAPTOP", "SKU-MOUSE"};

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(8);
        for (int t = 0; t < 8; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 1000; i++) {
                        String sku = skus[i % skus.length];
                        attemptsBySku.computeIfAbsent(sku, key -> new LongAdder()).increment();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdown();

        System.out.println("=== ConcurrentHashMap<String, LongAdder>: per-SKU attempt counters ===");
        attemptsBySku.forEach((sku, count) -> System.out.println("  " + sku + ": " + count.sum() + " attempts"));
    }

    /**
     * USE CASE: an append-only audit log of reservation events, written by
     * several worker threads and read/iterated by a "reporting" thread
     * concurrently. CopyOnWriteArrayList's iterator is a snapshot of the
     * backing array taken at {@code iterator()} call time — it can NEVER
     * throw {@code ConcurrentModificationException}, unlike iterating a
     * plain {@code ArrayList} that another thread is mutating. The cost:
     * every {@code add()} copies the ENTIRE backing array, which would be
     * disastrous for a write-heavy structure but is fine for something
     * appended occasionally and read/iterated often — the textbook
     * "reads vastly outnumber writes" fit this class is designed for.
     */
    private static void demonstrateCopyOnWriteAuditLog() throws InterruptedException {
        CopyOnWriteArrayList<String> auditLog = new CopyOnWriteArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);
        for (int t = 0; t < 4; t++) {
            int workerId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 25; i++) {
                        auditLog.add("worker-" + workerId + " reserved unit " + i);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        // A "reporting" read taken WHILE writers may still be appending.
        // This line racing against the writers above is exactly the point:
        // it demonstrates the iterator/snapshot safety, not a fixed count.
        int snapshotSizeDuringWrites = auditLog.size();
        latch.await();
        executor.shutdown();

        System.out.println("=== CopyOnWriteArrayList: reservation audit log ===");
        System.out.println("  size observed mid-write: " + snapshotSizeDuringWrites + ", final size: " + auditLog.size());
        System.out.println("  (iterating auditLog here, even mid-write, could never throw ConcurrentModificationException)");
    }

    /**
     * USE CASE: a producer/consumer order-intake pipeline. Multiple
     * producer threads simulate incoming order requests arriving
     * concurrently; a single consumer thread drains the queue and feeds
     * each request through {@code OrderService.placeOrder} sequentially.
     * <p>
     * WHY A BOUNDED QUEUE ({@code ArrayBlockingQueue}, capacity 10) AND NOT
     * UNBOUNDED: {@code put()} on a full bounded queue BLOCKS the producer
     * until the consumer catches up — this is deliberate BACKPRESSURE. A
     * fast producer facing a slow consumer is naturally throttled instead
     * of the queue growing without limit until the JVM runs out of memory.
     * An unbounded queue trades that safety for producers that never wait —
     * appropriate only when you're certain the consumer can always keep up
     * or memory pressure genuinely isn't a concern.
     * <p>
     * WHY A PLAIN (Module 1, unsafe) {@code Inventory} IS FINE HERE: exactly
     * ONE thread (the consumer) ever calls {@code reserve()}/{@code
     * release()} — there is no concurrent access to the map at all, so the
     * documented race condition simply cannot manifest. This is itself a
     * useful lesson: you don't always need a concurrent data structure:
     * funneling all mutation through a single consumer thread (this
     * pattern, sometimes called a "single-writer" or actor-style design) is
     * a legitimate alternative to locking, not just a limitation to work
     * around.
     */
    private static void demonstrateBlockingQueuePipeline() throws InterruptedException {
        System.out.println("=== BlockingQueue: producer/consumer order-intake pipeline ===");

        BlockingQueue<OrderRequest> intake = new ArrayBlockingQueue<>(10);

        Product laptop = new Product("SKU-LAPTOP", "Laptop", new BigDecimal("1200.00"));
        Inventory inventory = new Inventory();
        // 7 units for 9 incoming requests (3 producers x 3 requests each) -
        // deliberately scarce so the consumer's InsufficientStockException
        // handling path actually runs too.
        inventory.restock(laptop.sku(), 7);
        OrderService orderService = new OrderService(inventory);

        // A sentinel "poison pill" tells the consumer "no more work is
        // coming" via the normal queue mechanism, instead of a separate
        // shared `volatile boolean running` flag the consumer would
        // otherwise have to poll for on every iteration.
        OrderRequest poisonPill = new OrderRequest(null, List.of());

        Thread consumer = new Thread(() -> {
            try {
                while (true) {
                    OrderRequest request = intake.take(); // blocks until a producer offers work
                    if (request == poisonPill) {
                        break;
                    }
                    try {
                        Order order = orderService.placeOrder(request.customer(), request.lines());
                        System.out.println("  consumer processed: " + order);
                    } catch (InsufficientStockException e) {
                        System.out.println("  consumer rejected order: " + e.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "order-consumer");
        consumer.start();

        List<Thread> producers = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            int producerId = p;
            Thread producer = new Thread(() -> {
                try {
                    for (int i = 0; i < 3; i++) {
                        Customer customer = new Customer("CUST-P" + producerId + "-" + i,
                                "Producer" + producerId + "Customer" + i,
                                "p" + producerId + "-" + i + "@example.com");
                        // put() BLOCKS if the queue is already at capacity
                        // (10) instead of growing unbounded — see the
                        // backpressure discussion above.
                        intake.put(new OrderRequest(customer, List.of(new OrderLine(laptop, 1))));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "order-producer-" + p);
            producers.add(producer);
            producer.start();
        }

        for (Thread producer : producers) {
            producer.join();
        }
        intake.put(poisonPill);
        consumer.join();

        System.out.println("  final laptop stock: " + inventory.stockOf(laptop.sku()) + " (started at 7, 9 requests came in)");
    }
}
