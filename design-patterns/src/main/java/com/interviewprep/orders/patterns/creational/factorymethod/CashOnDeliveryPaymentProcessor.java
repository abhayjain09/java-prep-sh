package com.interviewprep.orders.patterns.creational.factorymethod;

import java.math.BigDecimal;

public class CashOnDeliveryPaymentProcessor implements PaymentProcessor {

    @Override
    public String charge(BigDecimal amount) {
        // Nothing to charge electronically now — collected on delivery.
        return "COD-PENDING-" + amount.hashCode();
    }

    @Override
    public String name() {
        return "CASH_ON_DELIVERY";
    }
}
