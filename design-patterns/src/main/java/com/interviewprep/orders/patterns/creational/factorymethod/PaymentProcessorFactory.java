package com.interviewprep.orders.patterns.creational.factorymethod;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * CORRECT — the "simple factory" idiom most teams actually use day to day:
 * one class, one method, one place to add a new payment method. This is what
 * replaces {@link NaivePaymentProcessorCreation} in practice.
 *
 * Adding "DIGITAL_WALLET" tomorrow means: add the enum constant to
 * {@link PaymentMethod} (compiler then forces you to notice every exhaustive
 * switch/map over it — see java-basics/README.md's OrderStatus discussion for
 * the same argument), add one line to {@link #SUPPLIERS} below, and every
 * caller of {@link #create(PaymentMethod)} — no matter how many places call
 * it — picks up the new type automatically. Compare the size of that diff to
 * finding and editing every if/else copy in {@link NaivePaymentProcessorCreation}.
 *
 * WHY A Map<PaymentMethod, Supplier<PaymentProcessor>> INSTEAD OF A SWITCH:
 * functionally equivalent to a switch expression here, but demonstrates a
 * complementary technique — registering creation logic as data (a map of
 * constructors) rather than code branches. This shape scales better if the
 * mapping needs to be built dynamically (e.g. plugins registering their own
 * processor at startup) since you can call {@code SUPPLIERS.put(...)} from
 * outside this class, something a switch statement can never support.
 */
public class PaymentProcessorFactory {

    private static final Map<PaymentMethod, Supplier<PaymentProcessor>> SUPPLIERS =
            new EnumMap<>(PaymentMethod.class);

    static {
        SUPPLIERS.put(PaymentMethod.CREDIT_CARD, CreditCardPaymentProcessor::new);
        SUPPLIERS.put(PaymentMethod.BANK_TRANSFER, BankTransferPaymentProcessor::new);
        SUPPLIERS.put(PaymentMethod.CASH_ON_DELIVERY, CashOnDeliveryPaymentProcessor::new);
    }

    public PaymentProcessor create(PaymentMethod method) {
        Supplier<PaymentProcessor> supplier = SUPPLIERS.get(method);
        if (supplier == null) {
            // Only reachable if a new enum constant is added without a
            // matching supplier registration — a much smaller, more
            // localized failure mode than the naive version's typo-prone
            // String matching.
            throw new IllegalStateException("No PaymentProcessor registered for " + method);
        }
        return supplier.get();
    }
}
