package com.interviewprep.orders.patterns.structural.composite;

import com.interviewprep.orders.patterns.behavioral.visitor.OrderComponentVisitor;

import java.math.BigDecimal;

/**
 * The COMPONENT interface Composite is built around: both a single
 * {@link ProductLeaf} and a {@link ProductBundle} (a bundle of other
 * components, possibly including other bundles) implement this SAME
 * interface, so calling code never needs to know or care whether it's
 * holding one product or a whole tree of them.
 *
 * WHY THIS MATTERS FOR THE DOMAIN: real e-commerce catalogs sell "bundles"
 * / "kits" (e.g. a gift set of 3 products at a combined price, or a bundle
 * containing another smaller bundle) alongside plain single products.
 * Pricing, weight, and tax logic needs to treat both uniformly.
 *
 * ALSO DOUBLES AS THE VISITOR PATTERN'S "ELEMENT" INTERFACE
 * ({@code accept(OrderComponentVisitor)}): Composite and Visitor are
 * frequently paired in real systems — a tree structure (Composite) plus
 * operations that traverse it without polluting the tree's classes with
 * every new operation (Visitor). See gof/behavioral/visitor for the visitor
 * side of this same class hierarchy; it's intentional that visitor imports
 * FROM this package rather than duplicating the hierarchy, to demonstrate
 * how GoF patterns compose together in real designs.
 */
public interface OrderComponent {
    BigDecimal price();
    String name();

    /** Double-dispatch hook consumed by gof/behavioral/visitor. */
    <R> R accept(OrderComponentVisitor<R> visitor);
}
