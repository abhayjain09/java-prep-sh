package com.interviewprep.orders.patterns.structural.decorator;

import com.interviewprep.orders.domain.Order;

import java.math.BigDecimal;

/**
 * WRONG — every optional surcharge is a boolean flag, and the price
 * calculation is one method with a combinatorial if/else for every
 * COMBINATION of flags (not just every flag individually).
 *
 * WHY THIS IS A PROBLEM:
 * 1. COMBINATORIAL GROWTH: 2 flags already need up to 4 branches to get the
 *    surcharge ORDER right (does gift wrap apply before or after express
 *    shipping's percentage fee? — with flags, that decision is buried
 *    inside one big method and easy to get inconsistent between branches).
 *    A third surcharge (say, "fragile handling fee") means auditing and
 *    updating every existing branch, not just adding one new independent
 *    unit.
 * 2. VIOLATES OPEN/CLOSED: adding a new surcharge type means editing this
 *    method's body — existing, already-tested logic — instead of adding a
 *    new, independently-testable unit.
 * 3. CAN'T REORDER OR SELECTIVELY COMPOSE AT RUNTIME: the surcharge order is
 *    hardcoded in the method; you can't decide at runtime "apply express
 *    shipping fee, then gift wrap, but skip tax" without yet more flags and
 *    yet more branches.
 *
 * Compare with the Decorator chain ({@link GiftWrapDecorator},
 * {@link ExpressShippingDecorator} wrapping {@link BaseOrderPricer}): each
 * surcharge is one small independent class, composed by simply nesting
 * constructors in whatever order the caller wants, with zero changes to
 * existing surcharge classes when a new one is added.
 */
public class NaiveOrderPriceCalculator {

    public BigDecimal calculatePrice(Order order, boolean giftWrap, boolean expressShipping) {
        BigDecimal price = order.totalAmount();

        if (giftWrap && expressShipping) {
            // Branch for "both" exists separately from "just one" branches
            // below — a sign the flags aren't really independent in this
            // design despite being modeled as independent booleans.
            price = price.add(new BigDecimal("5.00"));               // gift wrap flat fee
            price = price.add(price.multiply(new BigDecimal("0.15"))); // express % fee
        } else if (giftWrap) {
            price = price.add(new BigDecimal("5.00"));
        } else if (expressShipping) {
            price = price.add(price.multiply(new BigDecimal("0.15")));
        }
        // A third flag ("fragileHandling") would need FOUR more branches
        // (or a rewrite into nested ifs) to cover every combination.
        return price;
    }
}
