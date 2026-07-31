package com.interviewprep.orders.saga.orderplacement;

import java.math.BigDecimal;

/**
 * ILLUSTRATIVE — see ../SagaContext.java's header note on this LLD's scope
 * and conventions.
 *
 * Remote client interface for the Payment service — in a real system this
 * is the Anti-Corruption Layer (README.md Section 10) around a third-party
 * payment gateway's actual API/SDK, translating this domain's simple
 * charge/refund vocabulary into whatever that gateway's model requires.
 */
public interface PaymentServiceClient {

    /**
     * Charges the customer's payment method. Returns a paymentId used to
     * refund later. Throws on decline or a remote failure — either is
     * treated by ChargePaymentStep as this step's failure (see
     * ../../diagrams/saga-compensation-path.md for the resulting rollback).
     */
    String charge(String sagaId, String customerId, BigDecimal amount);

    /** Refunds a previous charge in full. MUST be idempotent. */
    void refund(String paymentId);
}
