package com.interviewprep.orders.jvm;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderLine;
import com.interviewprep.orders.domain.Product;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Simulates what "a high volume of Order objects per second" does to
 * young-generation GC pressure, and contrasts a wasteful allocation pattern
 * against a leaner one that produces the same business result.
 *
 * WHY THIS MATTERS: OrderService.placeOrder(...) (java-basics) already
 * allocates the minimum viable set of objects for one order — one Order,
 * one OrderLine per requested line. That's unavoidable given the domain
 * model and is NOT the problem. The problem, in real systems, is almost
 * always accidental EXTRA allocation layered on top of the necessary
 * allocation: defensive copies made and immediately discarded, intermediate
 * collections built just to compute one value, String concatenation in a
 * hot loop, boxing where a primitive would do. This class demonstrates
 * both shapes side by side against the same volume of orders.
 *
 * WHAT THIS CLASS DOES *NOT* DO: it does not measure real GC pause times or
 * allocation rates — this sandbox has no `java`/JFR/GC logging available.
 * The wall-clock numbers it prints are illustrative only (see the comment
 * at the bottom of main()) and should never be read as a benchmark result;
 * for that, see NaiveMicrobenchmarkPitfalls.java (what NOT to do) and
 * OrderServiceJmhBenchmark.java (the correct tool for the job).
 */
public class AllocationPatternsDemo {

    public static void main(String[] args) {
        Product product = new Product("SKU-BULK", "Bulk Item", new BigDecimal("4.50"));
        Customer customer = new Customer("CUST-BULK", "Bulk Customer", "bulk@example.com");

        // Stand-in for "a high volume of Order objects per second" — pick a
        // number large enough that, on a real JVM with a modest young gen,
        // it would trigger multiple minor GCs while this loop runs.
        int volume = 200_000;

        long start = System.nanoTime();
        long wastefulLineCount = wastefulHighAllocationVersion(product, customer, volume);
        long wastefulNanos = System.nanoTime() - start;

        start = System.nanoTime();
        long leanerLineCount = leanerLowerAllocationVersion(product, customer, volume);
        long leanerNanos = System.nanoTime() - start;

        System.out.println("wasteful version processed " + wastefulLineCount + " lines in "
                + (wastefulNanos / 1_000_000) + " ms");
        System.out.println("leaner   version processed " + leanerLineCount + " lines in "
                + (leanerNanos / 1_000_000) + " ms");

        // ILLUSTRATIVE NOTE (not a captured measurement — this sandbox
        // cannot run Java): on a real JVM you would expect the wasteful
        // version to be measurably slower AND to show a higher minor-GC
        // count in `-Xlog:gc` output for the same input volume, because it
        // allocates strictly more garbage per iteration for the same
        // business result. The actual ratio depends entirely on heap
        // sizing, collector choice, and JIT warm-up state — treat any
        // specific number you see when you run this yourself as a data
        // point to investigate with JFR/-Xlog:gc, not a benchmark result
        // (a single un-warmed-up run like this one is exactly the "naive
        // benchmark" anti-pattern section 6 / NaiveMicrobenchmarkPitfalls
        // warns about — it's used here only to make the demo runnable and
        // show *some* output, not to claim a valid measurement).
    }

    /**
     * Allocates strictly more than it needs to per order:
     *  1. builds the Order + OrderLine (unavoidable, same as the lean version)
     *  2. ALSO takes a defensive copy of order.getLines() and immediately
     *     discards it (order.getLines() itself already allocates via
     *     List.copyOf() — see java-basics Order.java — so this triples up
     *     on list allocation for no reason)
     *  3. ALSO builds a throwaway "audit" String via concatenation instead
     *     of just using the already-available Order.toString()
     *
     * Every one of these extra allocations is short-lived garbage — it
     * dies within the same iteration — so it lands in Eden and inflates
     * the rate at which Eden fills, directly increasing minor GC frequency
     * for zero behavioral benefit. This is the single most common shape of
     * "GC pressure" bug report in real production systems: not one giant
     * leak, but thousands of small, unnecessary allocations per request.
     */
    private static long wastefulHighAllocationVersion(Product product, Customer customer, int volume) {
        long lineCount = 0;
        for (int i = 0; i < volume; i++) {
            Order order = new Order("ORD-WASTEFUL-" + i, customer);
            order.addLine(new OrderLine(product, 1));

            // Unnecessary: getLines() already returns an immutable copy;
            // wrapping it in yet another ArrayList just to throw it away
            // is pure waste, but this exact pattern ("let me just copy this
            // into a mutable list real quick") is extremely common in
            // real code written under time pressure.
            List<OrderLine> throwawayCopy = new ArrayList<>(order.getLines());
            lineCount += throwawayCopy.size();

            // Unnecessary: string concatenation building an "audit" message
            // that's never used or logged — pure allocation for nothing.
            String throwawayAudit = "processed order " + order.id() + " with " + throwawayCopy.size() + " lines";
            if (throwawayAudit.isEmpty()) {
                // never true; just prevents an overly aggressive compiler
                // from proving the whole String is dead and removing the
                // concatenation before this demo even gets to show it off.
                lineCount--;
            }
        }
        return lineCount;
    }

    /**
     * Same business result (a count of order lines processed across
     * `volume` orders) with only the allocations the domain model actually
     * requires: one Order, one OrderLine, and reading its size directly
     * off the Order via getLines().size() exactly once (still one
     * List.copyOf() call inside Order — that one IS required by the
     * encapsulation design in java-basics/Order.java, since Order
     * deliberately never exposes its live internal list).
     */
    private static long leanerLowerAllocationVersion(Product product, Customer customer, int volume) {
        long lineCount = 0;
        for (int i = 0; i < volume; i++) {
            Order order = new Order("ORD-LEAN-" + i, customer);
            order.addLine(new OrderLine(product, 1));
            lineCount += order.getLines().size();
        }
        return lineCount;
    }
}
