package com.interviewprep.orders.patterns.behavioral.templatemethod;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderLine;

import java.util.List;

/**
 * WRONG — two order-processing classes, each hand-rolling the SAME
 * four-step skeleton (validate -> reserve stock -> charge -> notify),
 * differing only in ONE or TWO steps. Modeled here as two nested classes in
 * one file specifically so the duplication is visible side by side.
 *
 * WHY THIS IS A PROBLEM:
 * 1. THE SKELETON ITSELF IS DUPLICATED: both classes independently spell out
 *    "validate, then reserve, then charge, then notify, in that order." If
 *    the business decides charging must happen BEFORE reserving stock (a
 *    real, plausible policy change), that's a synchronized edit across
 *    EVERY processor class doing this by hand — easy to update one and
 *    forget the other, producing inconsistent order processing behavior
 *    between standard and express orders.
 * 2. THE SHARED STEPS DRIFT: {@code validate()} is copy-pasted identically
 *    here — but "identical today" doesn't mean "identical forever"; the
 *    next engineer fixing a validation bug might only think to fix it in
 *    the class they're looking at.
 * 3. NEW VARIANTS MEAN COPY-PASTING THE WHOLE SKELETON AGAIN: a third order
 *    type (e.g. "subscription renewal order") means a third class
 *    re-typing all four steps, three of which are identical to the other
 *    two classes.
 *
 * See {@link OrderProcessorTemplate}: the skeleton is written ONCE, in one
 * {@code final} method, and subclasses override ONLY the steps that
 * genuinely differ.
 */
public class NaiveDuplicatedOrderProcessors {

    public static class NaiveStandardOrderProcessor {
        private final Inventory inventory;

        public NaiveStandardOrderProcessor(Inventory inventory) {
            this.inventory = inventory;
        }

        public Order process(Customer customer, List<OrderLine> lines) {
            validate(lines);                                    // step 1 (shared)
            for (OrderLine line : lines) {                       // step 2 (shared)
                inventory.reserve(line.product().sku(), line.quantity());
            }
            System.out.println("Charging standard shipping rate"); // step 3 (VARIES)
            Order order = new Order("ORD-" + System.nanoTime(), customer);
            lines.forEach(order::addLine);
            System.out.println("Notified customer: standard delivery in 5-7 days"); // step 4 (VARIES)
            return order;
        }

        private void validate(List<OrderLine> lines) {
            if (lines.isEmpty()) {
                throw new IllegalArgumentException("Order must have at least one line");
            }
        }
    }

    public static class NaiveExpressOrderProcessor {
        private final Inventory inventory;

        public NaiveExpressOrderProcessor(Inventory inventory) {
            this.inventory = inventory;
        }

        public Order process(Customer customer, List<OrderLine> lines) {
            validate(lines);                                    // step 1 (IDENTICAL to standard — duplicated)
            for (OrderLine line : lines) {                       // step 2 (IDENTICAL to standard — duplicated)
                inventory.reserve(line.product().sku(), line.quantity());
            }
            System.out.println("Charging express shipping surcharge"); // step 3 (VARIES)
            Order order = new Order("ORD-" + System.nanoTime(), customer);
            lines.forEach(order::addLine);
            System.out.println("Notified customer: express delivery in 1-2 days"); // step 4 (VARIES)
            return order;
        }

        // Byte-for-byte identical to NaiveStandardOrderProcessor.validate —
        // copy-pasted, not shared.
        private void validate(List<OrderLine> lines) {
            if (lines.isEmpty()) {
                throw new IllegalArgumentException("Order must have at least one line");
            }
        }
    }
}
