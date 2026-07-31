package com.interviewprep.orders.patterns.behavioral.visitor;

import com.interviewprep.orders.patterns.structural.composite.ProductBundle;
import com.interviewprep.orders.patterns.structural.composite.ProductLeaf;

import java.math.BigDecimal;

/**
 * CORRECT — a second, independent operation added with ZERO changes to
 * {@code ProductLeaf}, {@code ProductBundle}, or {@link TaxCalculationVisitor}.
 * This is the payoff of Visitor: the {@link NaiveInstanceofOperations} bug
 * (forgetting to recurse into bundles) is structurally impossible here,
 * because {@link OrderComponentVisitor} forces {@code visit(ProductBundle)}
 * to be implemented before this class compiles.
 */
public class ShippingWeightVisitor implements OrderComponentVisitor<BigDecimal> {

    private static final BigDecimal PER_LEAF_WEIGHT_KG = BigDecimal.ONE;

    @Override
    public BigDecimal visit(ProductLeaf leaf) {
        return PER_LEAF_WEIGHT_KG;
    }

    @Override
    public BigDecimal visit(ProductBundle bundle) {
        BigDecimal total = BigDecimal.ZERO;
        for (var child : bundle.children()) {
            total = total.add(child.accept(this));
        }
        return total;
    }
}
