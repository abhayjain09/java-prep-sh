package com.interviewprep.orders.patterns.behavioral.visitor;

import com.interviewprep.orders.patterns.structural.composite.ProductBundle;
import com.interviewprep.orders.patterns.structural.composite.ProductLeaf;

import java.math.BigDecimal;

/**
 * CORRECT — one operation (sales tax calculation), fully separated from the
 * {@code OrderComponent} class hierarchy. The compiler (via
 * {@link OrderComponentVisitor}) guarantees this class handles BOTH element
 * types that exist today — there is no way to "forget" the bundle case the
 * way {@link NaiveInstanceofOperations#calculateShippingWeight} did, because
 * an incomplete implementation of this interface simply fails to compile.
 */
public class TaxCalculationVisitor implements OrderComponentVisitor<BigDecimal> {

    private static final BigDecimal TAX_RATE = new BigDecimal("0.08");

    @Override
    public BigDecimal visit(ProductLeaf leaf) {
        return leaf.price().multiply(TAX_RATE);
    }

    @Override
    public BigDecimal visit(ProductBundle bundle) {
        // Recursion is explicit and localized here, not duplicated per
        // operation — each child re-enters accept(this), so nested bundles
        // are handled automatically without this class needing to know how
        // deep the tree goes.
        BigDecimal total = BigDecimal.ZERO;
        for (var child : bundle.children()) {
            total = total.add(child.accept(this));
        }
        return total;
    }
}
