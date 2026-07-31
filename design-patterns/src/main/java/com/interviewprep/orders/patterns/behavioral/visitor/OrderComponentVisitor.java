package com.interviewprep.orders.patterns.behavioral.visitor;

import com.interviewprep.orders.patterns.structural.composite.ProductBundle;
import com.interviewprep.orders.patterns.structural.composite.ProductLeaf;

/**
 * CORRECT — the Visitor interface: one {@code visit(...)} overload per
 * concrete element type in the {@code OrderComponent} hierarchy (imported
 * from gof/structural/composite — see that package's {@code OrderComponent}
 * Javadoc for why Composite and Visitor are deliberately paired here).
 *
 * WHY GENERIC ON {@code R}: lets the SAME visitor shape support operations
 * that return a value (e.g. {@link TaxCalculationVisitor} returning a
 * BigDecimal) without forcing every visitor to either return void or cast an
 * Object — a small but real ergonomics win over the classic GoF Visitor
 * (which predates generics and typically returns void, stashing results in
 * a field).
 *
 * ADDING A NEW OPERATION (e.g. "compute shipping label weight") means
 * writing ONE new class implementing this interface — zero changes to
 * {@code ProductLeaf} or {@code ProductBundle}. Compare this to
 * {@link com.interviewprep.orders.patterns.behavioral.visitor.NaiveInstanceofOperations},
 * where every new operation means editing (or copy-pasting a new method
 * into) code that already knows about every concrete type.
 *
 * ADDING A NEW ELEMENT TYPE (e.g. a "DigitalProductLeaf"), by contrast, means
 * editing THIS interface and every existing visitor — the classic Visitor
 * trade-off (easy to add operations, hard to add element types). Choose
 * Visitor when your type hierarchy is stable but the set of operations over
 * it keeps growing — exactly the catalog/pricing/reporting scenario here.
 */
public interface OrderComponentVisitor<R> {
    R visit(ProductLeaf leaf);
    R visit(ProductBundle bundle);
}
