package com.interviewprep.orders.patterns.behavioral.mediator;

import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.InsufficientStockException;

import java.math.BigDecimal;

/**
 * WRONG — checkout "colleagues" hold DIRECT references to each other,
 * forming a tangled mesh where every object needs to know about every other
 * object it might need to trigger a reaction in.
 *
 * WHY THIS IS A PROBLEM:
 * 1. N-SQUARED COUPLING: with 3 colleagues (inventory-check, pricing,
 *    notification), this example already needs each one wired to the
 *    others it calls. A FOURTH colleague (e.g. a loyalty-points awarder
 *    that should also react to "stock reserved") means threading a new
 *    reference through EVERY existing colleague that needs to call it —
 *    coupling grows roughly with the SQUARE of the colleague count, not
 *    linearly.
 * 2. HARD TO REASON ABOUT THE WORKFLOW: the actual sequence of "what happens
 *    when stock is reserved" is scattered across whichever colleague
 *    happens to trigger the next step — there's no single place to read
 *    "this is the checkout workflow," you have to trace method calls across
 *    three classes.
 * 3. IMPOSSIBLE TO REUSE A COLLEAGUE IN A DIFFERENT WORKFLOW: this
 *    NaiveInventoryChecker can ONLY ever notify THIS specific
 *    NaivePricingCalculator and NaiveNotifier — it can't be reused in, say,
 *    a "restock" workflow with different collaborators, because the
 *    collaborators are hardwired as fields.
 *
 * See {@link CheckoutMediator} + {@link InventoryColleague} /
 * {@link PricingColleague} / {@link NotificationColleague}: each colleague
 * only knows the MEDIATOR interface — the actual workflow (what happens
 * after stock is reserved) lives in ONE place, the concrete mediator
 * implementation, and colleagues are reusable across different mediators/
 * workflows.
 */
public class NaiveDirectColleagueCommunication {

    public static class NaiveInventoryChecker {
        private final Inventory inventory;
        private final NaivePricingCalculator pricingCalculator; // direct reference
        private final NaiveNotifier notifier;                   // direct reference

        public NaiveInventoryChecker(Inventory inventory, NaivePricingCalculator pricingCalculator,
                                      NaiveNotifier notifier) {
            this.inventory = inventory;
            this.pricingCalculator = pricingCalculator;
            this.notifier = notifier;
        }

        public void tryReserve(String sku, int quantity, BigDecimal lineTotal) {
            try {
                inventory.reserve(sku, quantity);
                // Calls a SIBLING directly — this class now needs to know
                // PricingCalculator's exact API shape.
                pricingCalculator.calculateAndNotify(lineTotal, notifier);
            } catch (InsufficientStockException e) {
                notifier.notifyCustomer("Sorry, " + sku + " is out of stock");
            }
        }
    }

    public static class NaivePricingCalculator {
        public void calculateAndNotify(BigDecimal lineTotal, NaiveNotifier notifier) {
            BigDecimal withTax = lineTotal.add(lineTotal.multiply(new BigDecimal("0.08")));
            // Calls ANOTHER sibling directly.
            notifier.notifyCustomer("Your total is " + withTax);
        }
    }

    public static class NaiveNotifier {
        public void notifyCustomer(String message) {
            System.out.println("Notification: " + message);
        }
    }
}
