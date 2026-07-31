package com.interviewprep.orders.patterns.behavioral.strategy;

import java.math.BigDecimal;

/**
 * WRONG — the textbook Strategy anti-pattern: a long if/else (or switch)
 * chain keyed on a raw "type" string, embedded directly in the calculation
 * method, deciding which discount MATH to run.
 *
 * WHY THIS IS A PROBLEM (same Open/Closed argument as Factory Method's naive
 * example, but for BEHAVIOR/ALGORITHM selection instead of object creation):
 * 1. Adding a new discount rule (e.g. "BUY_ONE_GET_ONE") means editing this
 *    method — and every other method in the codebase that duplicates this
 *    same if/else to apply a discount (checkout preview, cart total display,
 *    final invoice calculation typically all need "the current discount"
 *    independently).
 * 2. THE MAGIC NUMBERS ARE BURIED INSIDE THE BRANCH: "0.10", "5.00" are
 *    hardcoded here — testing "10% discount" in isolation means invoking
 *    this whole method with the right string, rather than unit-testing a
 *    small, focused strategy class directly.
 * 3. TYPOS ARE RUNTIME BUGS: passing "PERCENTAGE " (trailing space) or
 *    "percentage" (wrong case) silently falls through to the else branch
 *    instead of failing to compile.
 *
 * Compare with {@link PricingContext}, which holds a {@link DiscountStrategy}
 * reference — swapping the algorithm is a constructor/setter call, not a
 * code edit, and each strategy is independently unit-testable.
 */
public class NaiveDiscountCalculator {

    public BigDecimal calculateDiscountedTotal(BigDecimal amount, String discountType) {
        if (discountType.equals("NONE")) {
            return amount;
        } else if (discountType.equals("PERCENTAGE")) {
            return amount.subtract(amount.multiply(new BigDecimal("0.10")));
        } else if (discountType.equals("FLAT")) {
            return amount.subtract(new BigDecimal("5.00")).max(BigDecimal.ZERO);
        } else {
            throw new IllegalArgumentException("Unknown discount type: " + discountType);
        }
    }
}
