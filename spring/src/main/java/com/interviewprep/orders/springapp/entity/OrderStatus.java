package com.interviewprep.orders.springapp.entity;

import java.util.Set;

/**
 * Same state machine as {@code java-basics/.../domain/OrderStatus.java},
 * redeclared here rather than imported.
 *
 * WHY A SEPARATE COPY INSTEAD OF DEPENDING ON java-basics: this module is
 * intentionally a standalone Maven project (its own {@code pom.xml}), not a
 * module that depends on java-basics' plain-Java source set. In a real
 * multi-module company monorepo you likely WOULD extract a shared
 * "domain-core" module both `java-basics` and `spring` depend on — but for
 * a teaching repo, keeping `spring/` self-contained means you can read this
 * one file and understand the whole persistence-layer story without
 * jumping between Maven modules or worrying about a shared-module build
 * order. The trade-off (duplicated ~20 lines) is deliberate and explained
 * again in spring/README.md's "Why this module defines its own entities"
 * section.
 *
 * WHY THIS ENUM ITSELF IS NOT THE {@code @Entity} AND HAS NO JPA ANNOTATIONS
 * OF ITS OWN: enums are mapped via the field that references them
 * ({@code @Enumerated(EnumType.STRING)} on {@code Order.status}, see below) —
 * the enum type itself stays a plain Java enum, unaware of persistence.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    /**
     * Pure query, no mutation — safe to call speculatively (e.g. a
     * controller deciding whether to render a "Cancel" button).
     */
    public boolean canTransitionTo(OrderStatus next) {
        return legalNextStates().contains(next);
    }

    private Set<OrderStatus> legalNextStates() {
        return switch (this) {
            case PENDING -> Set.of(CONFIRMED, CANCELLED);
            case CONFIRMED -> Set.of(SHIPPED, CANCELLED);
            case SHIPPED -> Set.of(DELIVERED);
            case DELIVERED -> Set.of(); // terminal
            case CANCELLED -> Set.of(); // terminal
        };
    }
}
