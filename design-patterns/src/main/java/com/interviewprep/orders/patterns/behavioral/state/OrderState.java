package com.interviewprep.orders.patterns.behavioral.state;

import java.math.BigDecimal;

/**
 * The STATE interface: each implementation IS one status of the order
 * lifecycle, and carries whatever BEHAVIOR/RULES vary per status —
 * cancellation fee, which statuses are legal to move to next, and whether
 * the order can still be edited.
 *
 * COMPARE WITH java-basics' {@code OrderStatus} enum: that enum's
 * {@code canTransitionTo()} answers ONE question (is this transition legal)
 * with a simple, fixed lookup table — perfectly adequate for a small,
 * stable set of rules. This interface answers SEVERAL questions per status,
 * with genuinely different formulas per status (not just a different
 * lookup table entry) — see {@link OrderStateContext}'s Javadoc for the
 * "when do you graduate from enum-with-behavior to full State pattern"
 * discussion this module was specifically asked to cover.
 */
public interface OrderState {

    /** The next state after a "progress the order" action, or throws if terminal/invalid. */
    OrderState next();

    /** Percentage (0.0-1.0) of order value charged if cancelled FROM this state. */
    BigDecimal cancellationFeePercentage();

    /** Whether lines can still be added/removed while in this state. */
    boolean isEditable();

    String name();
}
