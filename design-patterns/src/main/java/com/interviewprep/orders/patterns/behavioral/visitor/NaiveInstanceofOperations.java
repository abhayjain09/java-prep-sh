package com.interviewprep.orders.patterns.behavioral.visitor;

import com.interviewprep.orders.patterns.structural.composite.OrderComponent;
import com.interviewprep.orders.patterns.structural.composite.ProductBundle;
import com.interviewprep.orders.patterns.structural.composite.ProductLeaf;

import java.math.BigDecimal;

/**
 * WRONG — every new operation over the {@link OrderComponent} tree is
 * implemented as its own method full of {@code instanceof} checks and casts.
 *
 * WHY THIS IS A PROBLEM:
 * 1. DUPLICATED TYPE-SWITCHING LOGIC: {@link #calculateTax(OrderComponent)}
 *    and {@link #calculateShippingWeight(OrderComponent)} both re-derive
 *    "is this a leaf or a bundle, and if a bundle, recurse into children" —
 *    the exact same structural traversal, copy-pasted per operation. A third
 *    operation (say, "render receipt line") means a third copy of this same
 *    instanceof/recursion shape.
 * 2. EASY TO FORGET A CASE: nothing forces every instanceof chain to handle
 *    every current (or future) OrderComponent implementation — unlike a
 *    sealed-type exhaustive switch (java-basics/README.md) or the Visitor
 *    interface's compiler-enforced method set, a missing instanceof branch
 *    here just silently falls through (see the bug in
 *    {@link #calculateShippingWeight(OrderComponent)} below, which forgets to
 *    recurse into ProductBundle children).
 * 3. VIOLATES OPEN/CLOSED: adding a new OrderComponent implementation means
 *    finding and editing EVERY method like these two, in every class that
 *    has ever written one.
 *
 * Compare with {@link TaxCalculationVisitor} / {@link ShippingWeightVisitor}:
 * each new operation is a new class, and the compiler (via the
 * {@link OrderComponentVisitor} interface) forces every visitor to handle
 * every element type that exists today.
 */
public class NaiveInstanceofOperations {

    public BigDecimal calculateTax(OrderComponent component) {
        if (component instanceof ProductLeaf leaf) {
            return leaf.price().multiply(new BigDecimal("0.08"));
        } else if (component instanceof ProductBundle bundle) {
            BigDecimal total = BigDecimal.ZERO;
            for (OrderComponent child : bundle.children()) {
                total = total.add(calculateTax(child)); // manual recursion, rewritten per operation
            }
            return total;
        }
        throw new IllegalArgumentException("Unknown OrderComponent type: " + component.getClass());
    }

    /**
     * BUG: this method was written by copy-pasting calculateTax and forgot
     * to handle the ProductBundle case at all — it silently returns ZERO
     * weight for every bundle instead of recursing, understating shipping
     * weight for every order containing a bundle. This is exactly the class
     * of bug that duplicated instanceof-driven traversal invites, and
     * exactly what {@link ShippingWeightVisitor} (forced to implement BOTH
     * visit() overloads by the interface) cannot silently omit.
     */
    public BigDecimal calculateShippingWeight(OrderComponent component) {
        if (component instanceof ProductLeaf leaf) {
            return BigDecimal.ONE; // placeholder: 1kg per unit product, for illustration
        }
        // Missing "else if (component instanceof ProductBundle)" branch —
        // bundles fall through and are silently treated as weightless.
        return BigDecimal.ZERO;
    }
}
