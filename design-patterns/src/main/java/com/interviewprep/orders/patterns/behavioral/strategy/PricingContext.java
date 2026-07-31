package com.interviewprep.orders.patterns.behavioral.strategy;

import java.math.BigDecimal;

/**
 * CORRECT — the Strategy CONTEXT: holds a reference to whichever
 * {@link DiscountStrategy} applies, and delegates to it rather than
 * branching on a type code itself.
 *
 * USAGE EXAMPLE (swapping the algorithm is just passing a different object —
 * no code edit, no recompilation of this class):
 * <pre>{@code
 * PricingContext blackFriday = new PricingContext(new PercentageDiscountStrategy(new BigDecimal("0.25")));
 * PricingContext loyaltyCoupon = new PricingContext(new FlatAmountDiscountStrategy(new BigDecimal("10.00")));
 * PricingContext standard = new PricingContext(new NoDiscountStrategy());
 * }</pre>
 * Also note: {@link #setStrategy} lets the SAME context switch algorithms at
 * RUNTIME (e.g. a promotional strategy that only activates during a sale
 * window) — something a hardcoded if/else chain can't offer without editing
 * code and redeploying.
 */
public class PricingContext {

    private DiscountStrategy strategy;

    public PricingContext(DiscountStrategy strategy) {
        this.strategy = strategy;
    }

    public void setStrategy(DiscountStrategy strategy) {
        this.strategy = strategy;
    }

    public BigDecimal calculateDiscountedTotal(BigDecimal amount) {
        return strategy.apply(amount);
    }
}
