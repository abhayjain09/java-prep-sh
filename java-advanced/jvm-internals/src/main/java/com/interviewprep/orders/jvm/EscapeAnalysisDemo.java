package com.interviewprep.orders.jvm;

import com.interviewprep.orders.domain.OrderLine;
import com.interviewprep.orders.domain.Product;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Contrasts a NON-ESCAPING allocation pattern against an ESCAPING one,
 * using the same OrderLine construction, to illustrate what "escape
 * analysis" means well enough to reason about in an interview.
 *
 * WHAT ESCAPE ANALYSIS IS: a C2 (server compiler) optimization — see
 * diagrams/jit-tiered-compilation.md — that determines, for a HOT method,
 * whether an object created inside it can ever be observed from outside
 * that method/thread (stored in a field, returned, passed somewhere that
 * outlives the call, handed to another thread). If C2 can PROVE an object
 * never escapes, it can apply SCALAR REPLACEMENT: decompose the object into
 * its individual primitive/reference fields, keep those in registers or on
 * the stack, and skip allocating the object on the heap entirely. No heap
 * allocation means no GC involvement for that object at all.
 *
 * HONESTY NOTE: whether scalar replacement actually happens for a given
 * method depends on JVM version, the exact HotSpot build, method size,
 * inlining decisions, and warm-up state — it is NOT something you can
 * verify by reading source code, and this sandbox cannot run a profiler to
 * confirm it either way. The two methods below are written to make the
 * REASONING clear (one is a plausible non-escaping candidate, one
 * deliberately escapes and could never qualify) — treat "this WOULD be a
 * candidate" as a hypothesis you'd confirm with real diagnostics (see the
 * comment at the bottom of this file for the actual flags/tools), not as a
 * guaranteed outcome.
 */
public class EscapeAnalysisDemo {

    public static void main(String[] args) {
        Product product = new Product("SKU-WIDGET", "Widget", new BigDecimal("2.50"));
        int[] quantities = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

        BigDecimal total = sumLineTotalsNonEscaping(product, quantities);
        System.out.println("Total via non-escaping OrderLine usage: " + total);

        List<OrderLine> escaped = buildEscapingLines(product, quantities);
        System.out.println("Built " + escaped.size() + " OrderLines that escape into a returned list");
    }

    /**
     * NON-ESCAPING candidate: 'line' is created fresh on every loop
     * iteration, used exactly once (to call lineTotal()), and then nothing
     * in the rest of the program can ever reach it again — it isn't stored
     * in a field, isn't added to a collection, isn't returned, isn't
     * passed to another method that might retain it. Once this method is
     * hot enough to reach C2 (see jit-tiered-compilation.md), the compiler
     * has, in principle, everything it needs to prove 'line' never escapes
     * this loop iteration and apply scalar replacement — meaning the
     * OrderLine object itself may never actually be materialized on the
     * heap at all, even though the source code clearly says "new
     * OrderLine(...)" ten times per call.
     *
     * PRODUCTION RELEVANCE: this is exactly the shape of a hot pricing/
     * totals loop in an order-processing service — lots of short-lived
     * "scratch" objects used once and discarded. Escape analysis is part
     * of why such code, once warmed up, can allocate far less than a naive
     * reading of the source (or a COLD-code allocation profile taken
     * before JIT warm-up) would suggest.
     */
    public static BigDecimal sumLineTotalsNonEscaping(Product product, int[] quantities) {
        BigDecimal total = BigDecimal.ZERO;
        for (int qty : quantities) {
            OrderLine line = new OrderLine(product, qty); // does NOT escape this iteration
            total = total.add(line.lineTotal());
        }
        return total;
    }

    /**
     * ESCAPING: every 'line' created here is stored into 'lines', which is
     * then RETURNED from the method — by definition, the caller can hold
     * onto these OrderLine objects indefinitely. There is no way for the
     * JIT to prove they're unreachable after the method returns (because
     * they aren't), so scalar replacement is never a legal option here,
     * no matter how hot this method gets. This is the normal, expected
     * case for most object creation — most objects in a real system DO
     * need to outlive the method that created them (an OrderLine that will
     * actually be attached to a real Order and persisted is exactly this
     * shape) — escape analysis is the exception path, not the rule.
     */
    public static List<OrderLine> buildEscapingLines(Product product, int[] quantities) {
        List<OrderLine> lines = new ArrayList<>();
        for (int qty : quantities) {
            OrderLine line = new OrderLine(product, qty);
            lines.add(line); // escapes: reachable from 'lines', which escapes the method via return
        }
        return lines;
    }

    // HOW YOU WOULD ACTUALLY CONFIRM THIS ON A REAL JVM (not available in
    // this sandbox):
    //   1. Run sumLineTotalsNonEscaping in a loop millions of times so C2
    //      has a chance to compile it (see README section 4 on warm-up).
    //   2. Diagnostic flags (require -XX:+UnlockDiagnosticVMOptions, and
    //      full output typically needs a debug/fastdebug JDK build):
    //        -XX:+UnlockDiagnosticVMOptions -XX:+PrintEscapeAnalysis
    //        -XX:+UnlockDiagnosticVMOptions -XX:+PrintEliminateAllocations
    //   3. A production-build-friendly alternative: capture a JFR
    //      recording (see README section 5) and inspect the
    //      jdk.ObjectAllocationSample event's allocation-rate-by-call-site
    //      view — a method whose allocations are being scalar-replaced
    //      will show a lower sampled allocation rate for that object type
    //      than its source code would naively suggest, once it's hot.
}
