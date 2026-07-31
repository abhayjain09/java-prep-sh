package com.interviewprep.orders.patterns.structural.adapter;

/**
 * Our own domain exception, translated from the vendor's raw int status
 * code — another thing the Adapter is responsible for isolating: callers of
 * PaymentProcessor never see a vendor-specific status code at all.
 */
public class PaymentDeclinedException extends RuntimeException {
    private final int vendorStatusCode;

    public PaymentDeclinedException(int vendorStatusCode) {
        super("Payment declined (vendor status code " + vendorStatusCode + ")");
        this.vendorStatusCode = vendorStatusCode;
    }

    public int vendorStatusCode() {
        return vendorStatusCode;
    }
}
