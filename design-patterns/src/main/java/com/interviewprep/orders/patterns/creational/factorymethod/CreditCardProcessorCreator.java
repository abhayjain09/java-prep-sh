package com.interviewprep.orders.patterns.creational.factorymethod;

/** Concrete Creator: the factory method returns a CreditCardPaymentProcessor. */
public class CreditCardProcessorCreator extends PaymentProcessorCreator {
    @Override
    protected PaymentProcessor createProcessor() {
        return new CreditCardPaymentProcessor();
    }
}
