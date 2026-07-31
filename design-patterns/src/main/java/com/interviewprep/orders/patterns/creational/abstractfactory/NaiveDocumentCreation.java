package com.interviewprep.orders.patterns.creational.abstractfactory;

/**
 * WRONG — the client independently picks an Invoice implementation and a
 * Receipt implementation, each via its own if/else on a region string. There
 * is nothing in the type system stopping the two choices from disagreeing.
 *
 * THE BUG THIS ENABLES: a copy-paste error or a mid-refactor mistake produces
 * a US invoice paired with an EU receipt for the same order — a real, costly
 * bug class (mismatched tax jurisdictions on paired documents can be a
 * compliance problem, not just a cosmetic one). Nothing here enforces "the
 * invoice and the receipt must belong to the SAME family" — that invariant
 * lives only in the programmer's head, not in the code.
 *
 * This is precisely the problem Abstract Factory solves: bundle related
 * "product" creation behind ONE factory per family, so it's structurally
 * impossible to request a US invoice from the EU factory. See
 * {@link OrderDocumentFactory} and its two implementations.
 */
public class NaiveDocumentCreation {

    public Invoice createInvoice(String region) {
        if (region.equals("US")) {
            return new UsInvoice();
        } else if (region.equals("EU")) {
            return new EuInvoice();
        }
        throw new IllegalArgumentException("Unknown region: " + region);
    }

    public Receipt createReceipt(String region) {
        if (region.equals("US")) {
            return new UsReceipt();
        } else if (region.equals("EU")) {
            return new EuReceipt();
        }
        throw new IllegalArgumentException("Unknown region: " + region);
    }

    /**
     * The bug in action: a typo/mistake in ONE of the two calls silently
     * produces a mismatched document pair. Nothing here — not the compiler,
     * not a runtime check — catches it.
     */
    public void demonstrateMismatchBug() {
        Invoice invoice = createInvoice("US");
        Receipt receipt = createReceipt("EU"); // <-- should have been "US" too
        // invoice and receipt are now from different regional families,
        // and both objects look individually valid.
        System.out.println(invoice.render());
        System.out.println(receipt.render());
    }
}
