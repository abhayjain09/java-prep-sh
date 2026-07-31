package com.interviewprep.orders.patterns.structural.adapter;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * CORRECT — the Adapter: implements OUR {@link PaymentProcessor} interface
 * by wrapping (composing) the incompatible {@link ThirdPartyPaymentGateway}
 * and translating between the two shapes in exactly ONE place.
 *
 * This is "object adapter" style (composition, holding a reference to the
 * adaptee) rather than "class adapter" style (multiple inheritance via
 * extending the adaptee) — Java doesn't support multiple inheritance of
 * classes anyway, so object adapter is the idiomatic (and only) choice here,
 * and it has the added benefit of being able to adapt a FINAL third-party
 * class, which class adapter could never do.
 *
 * Everything checkout code needs to know is: "call charge(amount), get a
 * String confirmation id back, or an unchecked exception on failure." The
 * cents conversion, the status-code interpretation, and the vendor-specific
 * reference lookup all live here and nowhere else. Swapping payment
 * providers later means writing one new Adapter class — checkout code is
 * completely unaffected because it only ever depended on PaymentProcessor.
 */
public class PaymentGatewayAdapter implements PaymentProcessor {

    private final ThirdPartyPaymentGateway gateway;
    private final String cardLast4;

    public PaymentGatewayAdapter(ThirdPartyPaymentGateway gateway, String cardLast4) {
        this.gateway = gateway;
        this.cardLast4 = cardLast4;
    }

    @Override
    public String charge(BigDecimal amount) {
        long amountCents = toCents(amount);
        int statusCode = gateway.authorizeCharge(cardLast4, amountCents);
        if (statusCode != 0) {
            throw new PaymentDeclinedException(statusCode);
        }
        return gateway.lastTransactionReference();
    }

    private long toCents(BigDecimal amountDollars) {
        return amountDollars.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
