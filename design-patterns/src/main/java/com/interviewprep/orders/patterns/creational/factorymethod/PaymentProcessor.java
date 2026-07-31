package com.interviewprep.orders.patterns.creational.factorymethod;

import java.math.BigDecimal;

/**
 * The "product" interface this Factory Method example creates instances of.
 *
 * Kept local to this pattern's package (rather than shared across every
 * pattern that touches payments) so this folder is self-contained and
 * readable in isolation — a deliberate teaching-repo trade-off documented in
 * design-patterns/README.md. In a real codebase this would live in one place.
 */
public interface PaymentProcessor {

    /** Charges {@code amount} and returns a provider confirmation id. */
    String charge(BigDecimal amount);

    String name();
}
