package com.interviewprep.orders.domain;

import java.util.Set;

/**
 * The lifecycle states of an Order, plus the legal transitions between them.
 *
 * WHY BEHAVIOR ON AN ENUM (not a separate hierarchy of classes): the set of
 * statuses is small, fixed, and known at compile time — exactly what enums
 * are for. Encoding the transition rules here means "can a PENDING order
 * become DELIVERED directly?" is answered in exactly one place instead of
 * scattered across every call site that changes an order's status. If this
 * state machine grew large numbers of statuses with complex, configurable
 * transition rules, the State design pattern (Module 4) would be a better
 * fit than cramming more logic into the enum.
 *
 * Uses a Java 14+ switch EXPRESSION (the "->" arrow form): no fall-through,
 * every arm yields a value directly, and the compiler enforces exhaustiveness
 * over the enum's constants — leaving out a case is a compile error, not a
 * silent runtime bug. That exhaustiveness checking is the whole point of
 * modeling this as a closed enum rather than open-ended string statuses.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    /**
     * Returns whether transitioning from this status to {@code next} is a
     * legal business operation. Called by Order.transitionTo() before ever
     * mutating state, so illegal transitions (e.g. DELIVERED -> PENDING)
     * are rejected before they can happen, not detected after the fact.
     */
    public boolean canTransitionTo(OrderStatus next) {
        return legalNextStates().contains(next);
    }

    private Set<OrderStatus> legalNextStates() {
        return switch (this) {
            case PENDING -> Set.of(CONFIRMED, CANCELLED);
            case CONFIRMED -> Set.of(SHIPPED, CANCELLED);
            case SHIPPED -> Set.of(DELIVERED);
            case DELIVERED -> Set.of(); // terminal state — no legal transitions out
            case CANCELLED -> Set.of(); // terminal state — no legal transitions out
        };
    }
}
