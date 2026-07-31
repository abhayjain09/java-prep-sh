package com.interviewprep.orders.patterns.structural.adapter;

/**
 * SIMULATES a third-party payment gateway SDK we do NOT own or control — the
 * kind that ships as a compiled JAR with an API shaped by someone else's
 * conventions, not ours. We cannot edit this class (in a real project it
 * would live in a vendor dependency), and it does not implement our
 * {@link PaymentProcessor} interface — nor should we expect it to; that
 * would require every third-party library we ever integrate to coincidentally
 * match our internal interfaces, which never happens in practice.
 *
 * Notice the interface mismatch on purpose:
 *  - amounts are in MINOR UNITS (cents) as a {@code long}, not BigDecimal dollars
 *  - card data is a raw PAN string parameter instead of a domain type
 *  - the result is an int STATUS CODE (0 = approved, vendor-specific
 *    negative codes for various declines), not a confirmation id string
 */
public class ThirdPartyPaymentGateway {

    /**
     * @param panLast4  last 4 digits of the card (simulating a tokenized PAN
     *                  reference — never handle/store a full PAN yourself)
     * @param amountCents amount to charge, in minor currency units
     * @return 0 for approved, a negative vendor status code otherwise
     */
    public int authorizeCharge(String panLast4, long amountCents) {
        if (amountCents <= 0) {
            return -1; // vendor code: invalid amount
        }
        // Simulates a successful authorization.
        return 0;
    }

    /** Vendor-specific way to fetch a reference id for an approved charge. */
    public String lastTransactionReference() {
        return "TPG-REF-" + System.nanoTime();
    }
}
