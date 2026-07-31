package com.interviewprep.orders.patterns.creational.factorymethod;

import java.math.BigDecimal;

public class CreditCardPaymentProcessor implements PaymentProcessor {

    @Override
    public String charge(BigDecimal amount) {
        // In production this would call a real payment gateway SDK.
        return "CC-CONFIRM-" + amount.hashCode();
    }

    @Override
    public String name() {
        return "CREDIT_CARD";
    }
}
