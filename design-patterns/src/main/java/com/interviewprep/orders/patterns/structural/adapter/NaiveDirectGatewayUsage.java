package com.interviewprep.orders.patterns.structural.adapter;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * WRONG — checkout code talks directly to the third-party SDK's shape
 * (cents, status codes, vendor-specific reference lookup) instead of our own
 * {@link PaymentProcessor} abstraction.
 *
 * WHY THIS IS A PROBLEM:
 * 1. THE DOLLARS-TO-CENTS TRANSLATION IS DUPLICATED at every call site that
 *    charges a card — {@code amount.multiply(BigDecimal.valueOf(100))
 *    .longValue()} appears here and would need to appear again in refunds,
 *    subscription billing, admin tools, etc. Any inconsistency (e.g. one
 *    call site uses {@code .longValue()} — truncating — and another
 *    correctly rounds) is a silent money-losing bug.
 * 2. VENDOR LOCK-IN LEAKS INTO BUSINESS LOGIC: if the company switches
 *    payment providers, every one of these call sites must be found and
 *    rewritten, because the vendor's status-code/reference-lookup API is
 *    woven directly into checkout logic instead of isolated behind one seam.
 * 3. UNTESTABLE WITHOUT THE REAL SDK: unit-testing checkout logic now
 *    requires either a real (or heavily mocked) ThirdPartyPaymentGateway,
 *    because there's no PaymentProcessor abstraction to substitute a simple
 *    test double for.
 *
 * See {@link PaymentGatewayAdapter} for the fix: translate once, in one
 * class, and let checkout code depend only on {@link PaymentProcessor}.
 */
public class NaiveDirectGatewayUsage {

    private final ThirdPartyPaymentGateway gateway = new ThirdPartyPaymentGateway();

    public String chargeCustomer(String cardLast4, BigDecimal amountDollars) {
        // Dollars -> cents translation duplicated at every call site like
        // this one across the codebase.
        long amountCents = amountDollars.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();

        int statusCode = gateway.authorizeCharge(cardLast4, amountCents);
        if (statusCode != 0) {
            // Vendor-specific status code interpretation duplicated too.
            throw new IllegalStateException("Gateway declined charge, code=" + statusCode);
        }
        return gateway.lastTransactionReference();
    }
}
