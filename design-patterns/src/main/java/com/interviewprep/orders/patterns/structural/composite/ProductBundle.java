package com.interviewprep.orders.patterns.structural.composite;

import com.interviewprep.orders.patterns.behavioral.visitor.OrderComponentVisitor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The COMPOSITE pattern's COMPOSITE: a named bundle/kit made of other
 * {@link OrderComponent}s — which may themselves be {@link ProductLeaf}s OR
 * other {@code ProductBundle}s (nesting is exactly what makes this a tree,
 * not just a flat list).
 *
 * {@code price()} recurses uniformly over children regardless of whether
 * each child is a leaf or another bundle — the calling code (and this
 * class itself) never needs an {@code instanceof} check, which is the whole
 * point of giving both leaf and composite the same {@link OrderComponent}
 * interface.
 */
public final class ProductBundle implements OrderComponent {

    private final String bundleName;
    private final List<OrderComponent> children = new ArrayList<>();
    private final BigDecimal bundleDiscount; // flat discount applied to the sum of children

    public ProductBundle(String bundleName, BigDecimal bundleDiscount) {
        this.bundleName = bundleName;
        this.bundleDiscount = bundleDiscount;
    }

    public ProductBundle add(OrderComponent component) {
        children.add(component);
        return this;
    }

    @Override
    public BigDecimal price() {
        BigDecimal sum = children.stream()
                .map(OrderComponent::price) // recursion happens here — a child
                                             // that is itself a ProductBundle
                                             // calls its OWN price(), summing
                                             // its own children first
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.subtract(bundleDiscount).max(BigDecimal.ZERO);
    }

    @Override
    public String name() {
        return bundleName;
    }

    public List<OrderComponent> children() {
        return List.copyOf(children);
    }

    @Override
    public <R> R accept(OrderComponentVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
