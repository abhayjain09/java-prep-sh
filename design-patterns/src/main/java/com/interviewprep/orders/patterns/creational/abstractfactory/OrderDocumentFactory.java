package com.interviewprep.orders.patterns.creational.abstractfactory;

/**
 * CORRECT — the Abstract Factory: one interface that creates an entire FAMILY
 * of related products (an {@link Invoice} AND a {@link Receipt}) together.
 *
 * WHY THIS FIXES THE BUG in {@link NaiveDocumentCreation}: a caller holding a
 * single {@code OrderDocumentFactory} reference (say, {@code EuOrderDocumentFactory})
 * can only ever get EU-family products out of it — there is no code path that
 * lets you call {@code createInvoice()} on the EU factory and {@code
 * createReceipt()} on the US factory, because you only ever hold ONE factory
 * reference per region. The family-consistency invariant is enforced by the
 * type system/object graph instead of by programmer discipline.
 *
 * GRASP tie-in: this is also a form of Protected Variations (see GRASP.md) —
 * the checkout code that calls this interface is protected from ever knowing
 * which region's concrete classes exist.
 */
public interface OrderDocumentFactory {
    Invoice createInvoice();
    Receipt createReceipt();
}
