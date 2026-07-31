package com.interviewprep.orders.patterns.behavioral.state;

import com.interviewprep.orders.domain.OrderStatus;

import java.math.BigDecimal;

/**
 * WRONG (in the sense this module means it — see the class Javadoc on
 * {@link OrderStateContext} for the nuanced "when is enum-with-behavior
 * actually fine" answer) — as business rules per status grow BEYOND a
 * simple transition-legality lookup, cramming them into more and more
 * switch statements over the plain {@code OrderStatus} enum (or worse, an
 * ad hoc if/else on {@code order.status()} scattered across services)
 * reproduces the SAME growth problem Strategy/State are designed to solve.
 *
 * THIS FILE DELIBERATELY EXTENDS java-basics' OrderStatus enum's ORIGINAL,
 * SIMPLE job (just "is this transition legal," which the enum still does
 * perfectly well) with TWO MORE concerns bolted on as separate switch
 * statements in a service class — showing what happens once a THIRD, FOURTH
 * concern (fees, editability, and beyond) piles onto status-based logic
 * without ever refactoring the shape.
 *
 * WHY THIS GETS WORSE OVER TIME:
 * 1. THE SWITCHES MULTIPLY, NOT THE ENUM: every new per-status RULE (not
 *    just new statuses) means a new switch statement somewhere, each one
 *    re-deriving "what state am I in" from the same enum value, all needing
 *    to be kept consistent with each other by hand.
 * 2. LOGIC FOR ONE STATUS IS SCATTERED ACROSS MULTIPLE METHODS/CLASSES
 *    instead of living together: everything about "what SHIPPED means" here
 *    is split between {@link #cancellationFeePercentage} and
 *    {@link #isEditable} (and would be split further with each new rule) —
 *    compare with {@code ShippedState}, where every SHIPPED-specific rule
 *    lives in ONE class.
 * 3. EXHAUSTIVENESS CHECKING GETS DILUTED: the enum switch expression itself
 *    still catches a missing case at compile time (a genuine strength of
 *    enums — see java-basics/README.md) — but that only helps for switches
 *    that actually exist; nothing stops a new switch statement on
 *    {@code OrderStatus} from being written elsewhere as a plain if/else
 *    without the same protection.
 *
 * THE GRADUATION RULE OF THUMB (asked for explicitly in this module): keep
 * enum-with-behavior (java-basics' OrderStatus.canTransitionTo) as long as
 * you have ONE simple, table-shaped concern (legal transitions). Reach for
 * full State (this package) once you have MULTIPLE, GENUINELY DIFFERENT
 * per-status BEHAVIORS/FORMULAS (not just data lookups) that keep growing —
 * that's the point where each status deserves to be its own class instead
 * of another switch arm.
 */
public class NaiveStatusConditionals {

    public BigDecimal cancellationFeePercentage(OrderStatus status) {
        return switch (status) {
            case PENDING -> BigDecimal.ZERO;
            case CONFIRMED -> new BigDecimal("0.05");
            case SHIPPED -> new BigDecimal("0.20");
            case DELIVERED, CANCELLED -> throw new IllegalStateException("Cannot cancel from " + status);
        };
    }

    // A SECOND switch statement, re-deriving similar per-status knowledge
    // that already lives (differently shaped) in the method above — the
    // duplication/scattering problem in action.
    public boolean isEditable(OrderStatus status) {
        return switch (status) {
            case PENDING -> true;
            case CONFIRMED, SHIPPED, DELIVERED, CANCELLED -> false;
        };
    }

    // A THIRD switch would be needed for every future per-status rule
    // (e.g. "can a customer add a gift note in this status?"), each one a
    // new place the "SHIPPED" story is told, slightly differently.
}
