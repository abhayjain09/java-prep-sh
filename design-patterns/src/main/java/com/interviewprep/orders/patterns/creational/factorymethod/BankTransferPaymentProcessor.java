package com.interviewprep.orders.patterns.creational.factorymethod;

import java.math.BigDecimal;

public class BankTransferPaymentProcessor implements PaymentProcessor {

    @Override
    public String charge(BigDecimal amount) {
        return "ACH-CONFIRM-" + amount.hashCode();
    }

    @Override
    public String name() {
        return "BANK_TRANSFER";
    }
}
