package com.interviewprep.orders.jvm;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderLine;
import com.interviewprep.orders.domain.Product;
import com.interviewprep.orders.service.OrderService;
import com.interviewprep.orders.domain.Inventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately-flawed "System.currentTimeMillis() around a loop"
 * benchmark of OrderService.totalSpentByImperative(...) vs.
 * totalSpentByStreams(...) — written to be run and to visibly demonstrate
 * why this style of measurement is unreliable, NOT as a template to copy.
 *
 * See OrderServiceJmhBenchmark.java for the correct tool for this exact
 * comparison (JMH), and README section 6 for the full write-up.
 *
 * THE THREE PROBLEMS THIS CLASS DEMONSTRATES:
 *
 * 1. JIT WARM-UP: the first iterations of ANY loop run interpreted (or at
 *    best C1-compiled) — see diagrams/jit-tiered-compilation.md. A single
 *    timed pass that includes those early iterations blends "cold JVM"
 *    performance with "warmed up" performance into one misleading number.
 *    Worse, if you only run the loop ONCE per JVM invocation (as most
 *    naive benchmarks do), you may never leave the cold tiers at all for
 *    an infrequently-hit inner method.
 *
 * 2. DEAD CODE ELIMINATION (DCE): if the JIT can prove a computed value is
 *    never used for anything observable (never printed, returned, or
 *    stored somewhere that outlives the method), it is free to delete the
 *    computation ENTIRELY. A loop that calls
 *    orderService.totalSpentByStreams(...) and discards the result on
 *    every iteration is, from the compiler's point of view, dead code —
 *    an aggressive enough JIT (or even just C2 hoisting/eliminating
 *    redundant computation) could reduce "compute this a million times"
 *    down to "compute it zero times," making your benchmark measure
 *    approximately nothing while still reporting a suspiciously fast time.
 *
 * 3. NO CONTROL OVER JVM STATE: a single in-process loop shares its JVM
 *    with whatever else is happening — background GC from OTHER code
 *    running earlier, unrelated class loading/JIT compilation threads
 *    competing for CPU, OS scheduling noise. One run is one noisy sample,
 *    not a statistically meaningful measurement; there's no fork isolation
 *    (JMH forks a fresh JVM per benchmark specifically to avoid this).
 *
 * WHAT THIS CLASS DOES TO PARTIALLY (NOT FULLY) MITIGATE (1) AND (2), TO
 * MAKE THE DEMO AT LEAST SOMEWHAT HONEST:
 *  - it runs several timed passes and prints EACH one, so you can visually
 *    see the "fast-then-faster" warm-up curve instead of a single number
 *    that hides it;
 *  - it accumulates every result into a field and prints a checksum at the
 *    end, which discourages (but, without JMH's Blackhole, does not
 *    GUARANTEE against) the JIT eliminating the computation as dead code.
 */
public class NaiveMicrobenchmarkPitfalls {

    // Accumulating into a field that gets printed at the end is a common
    // manual anti-DCE trick. It's weaker than JMH's Blackhole.consume(...)
    // (which is specifically designed, with JIT-aware intrinsics, to defeat
    // this class of optimization) but better than discarding the result
    // outright.
    private static BigDecimal checksum = BigDecimal.ZERO;

    public static void main(String[] args) {
        OrderService orderService = buildOrderServiceWithSampleData();
        Customer customer = SAMPLE_CUSTOMER;
        List<Order> orders = SAMPLE_ORDERS;

        int callsPerPass = 100_000;
        int passes = 5;

        System.out.println("=== NAIVE BENCHMARK: totalSpentByStreams, " + passes
                + " passes of " + callsPerPass + " calls each ===");
        System.out.println("(Watch the wall-clock time SHRINK across passes on a real JVM — that");
        System.out.println(" shrinkage IS the JIT warm-up curve. A benchmark that only ran pass 1");
        System.out.println(" would report a number that has nothing to do with steady-state speed.)");
        for (int pass = 1; pass <= passes; pass++) {
            long start = System.currentTimeMillis(); // <-- the classic, flawed pattern
            for (int i = 0; i < callsPerPass; i++) {
                BigDecimal result = orderService.totalSpentByStreams(customer, orders);
                checksum = checksum.add(result); // weak anti-DCE guard, see class Javadoc
            }
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("  pass " + pass + ": " + elapsed + " ms for " + callsPerPass + " calls");
        }
        System.out.println("checksum (forces the JIT to keep the computation live): " + checksum);

        System.out.println();
        System.out.println("=== ILLUSTRATIVE EXAMPLE OUTPUT ONLY (NOT a real captured run — this ===");
        System.out.println("=== sandbox has no `java` binary to execute this class) =============");
        System.out.println("  pass 1: 44 ms   <- mostly interpreter + C1, includes class-load cost");
        System.out.println("  pass 2: 9 ms    <- C1 fully warmed, C2 compilation likely triggered mid-pass");
        System.out.println("  pass 3: 3 ms    <- C2-compiled steady state");
        System.out.println("  pass 4: 3 ms    <- steady state confirmed");
        System.out.println("  pass 5: 3 ms    <- steady state confirmed");
        System.out.println("  (Real numbers depend entirely on hardware, JVM version, and background");
        System.out.println("   load. The SHAPE -- a big drop then a plateau -- is the point, not the");
        System.out.println("   specific milliseconds. Never quote milliseconds from a naive benchmark");
        System.out.println("   like this in a design doc or a PR description.)");
    }

    // --- sample data setup, mirrors java-basics Main.java ---

    private static final Customer SAMPLE_CUSTOMER =
            new Customer("CUST-1", "Ada Lovelace", "ada@example.com");
    private static final List<Order> SAMPLE_ORDERS = buildSampleOrders();

    private static OrderService buildOrderServiceWithSampleData() {
        Inventory inventory = new Inventory();
        inventory.restock("SKU-LAPTOP", 1_000_000);
        inventory.restock("SKU-MOUSE", 1_000_000);
        return new OrderService(inventory);
    }

    private static List<Order> buildSampleOrders() {
        Product laptop = new Product("SKU-LAPTOP", "Laptop", new BigDecimal("1200.00"));
        Product mouse = new Product("SKU-MOUSE", "Wireless Mouse", new BigDecimal("25.00"));

        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Order order = new Order("ORD-" + i, SAMPLE_CUSTOMER);
            order.addLine(new OrderLine(laptop, 1));
            order.addLine(new OrderLine(mouse, 2));
            orders.add(order);
        }
        return orders;
    }
}
