package com.interviewprep.orders.patterns.creational.factorymethod;

import java.math.BigDecimal;

/**
 * WRONG — object creation logic (an if/else chain keyed on a raw String) is
 * baked directly into the client/caller instead of delegated to a dedicated
 * creator.
 *
 * WHY THIS IS A PROBLEM (Open/Closed Principle violation — see SOLID.md):
 * every time the business adds a new payment method (e.g. "DIGITAL_WALLET"),
 * this method — and, in a real codebase, every OTHER method that ever needs
 * to create a PaymentProcessor — must be found and edited. Checkout flows in
 * real systems typically construct payment processors in 3-5 different
 * places (order placement, refunds, subscription renewals, admin tools,
 * batch reconciliation jobs); a naive if/else chain gets copy-pasted into
 * every one of them, and they drift out of sync (one gets the new payment
 * type added, three don't).
 *
 * It's also a testing problem: to unit test "checkout with an unsupported
 * payment type" you must exercise this entire method's string-matching logic
 * instead of just injecting a test double that implements PaymentProcessor.
 *
 * Compare with {@link PaymentProcessorFactory}, which centralizes creation in
 * exactly one place — adding a new type means touching one method, in one
 * class, and every call site automatically benefits.
 */
public class NaivePaymentProcessorCreation {

    /**
     * Every caller that needs a processor duplicates logic shaped like this.
     * The "type" parameter is a raw String — nothing stops a caller from
     * passing "CREDITCARD" (typo) and getting a confusing
     * IllegalArgumentException deep in checkout instead of a compile error.
     */
    public PaymentProcessor createProcessor(String type) {
        if (type.equals("CREDIT_CARD")) {
            return new CreditCardPaymentProcessor();
        } else if (type.equals("BANK_TRANSFER")) {
            return new BankTransferPaymentProcessor();
        } else if (type.equals("CASH_ON_DELIVERY")) {
            return new CashOnDeliveryPaymentProcessor();
        } else {
            throw new IllegalArgumentException("Unknown payment type: " + type);
        }
    }

    public String chargeOrder(String paymentType, BigDecimal amount) {
        // A second copy of the SAME if/else logic living in a sibling method —
        // exactly the duplication a real codebase accumulates over time
        // without a single creation point.
        PaymentProcessor processor = createProcessor(paymentType);
        return processor.charge(amount);
    }
}
