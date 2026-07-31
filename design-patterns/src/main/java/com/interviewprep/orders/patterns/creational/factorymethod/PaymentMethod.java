package com.interviewprep.orders.patterns.creational.factorymethod;

/**
 * A closed, compile-time-known set of payment methods — replaces the raw
 * String "type" parameter from {@link NaivePaymentProcessorCreation}. Passing
 * an unsupported value is now a compile error, not a runtime
 * IllegalArgumentException discovered in production (the same enum-over-
 * String argument made for OrderStatus in java-basics/README.md).
 */
public enum PaymentMethod {
    CREDIT_CARD,
    BANK_TRANSFER,
    CASH_ON_DELIVERY
}
