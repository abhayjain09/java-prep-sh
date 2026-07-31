package com.interviewprep.orders.saga.orderplacement;

import com.interviewprep.orders.saga.SagaContext;
import com.interviewprep.orders.saga.SagaStep;

import java.math.BigDecimal;

/**
 * ILLUSTRATIVE — see ../SagaContext.java's header note on this LLD's scope
 * and conventions.
 *
 * The step whose failure is used as the running example throughout
 * README.md Section 6 and ../../diagrams/saga-compensation-path.md: if
 * the customer's card is declined here, ReserveInventoryStep and
 * CreateOrderStep (both already completed) are compensated in that
 * reverse order — ChargePaymentStep itself needs no compensation, since
 * it never completed.
 *
 * Context keys used:
 *   reads:  "customerId" (String), "totalAmount" (BigDecimal)
 *   writes: "paymentId" (String)
 */
public class ChargePaymentStep implements SagaStep {

    private final PaymentServiceClient paymentClient;

    public ChargePaymentStep(PaymentServiceClient paymentClient) {
        this.paymentClient = paymentClient;
    }

    @Override
    public String name() {
        return "ChargePayment";
    }

    @Override
    public void execute(SagaContext context) throws Exception {
        String customerId = context.get("customerId", String.class);
        BigDecimal amount = context.get("totalAmount", BigDecimal.class);
        String paymentId = paymentClient.charge(context.sagaId(), customerId, amount);
        context.put("paymentId", paymentId);
    }

    @Override
    public void compensate(SagaContext context) {
        String paymentId = context.get("paymentId", String.class);
        if (paymentId != null) {
            // Only non-null if the charge actually succeeded before a LATER
            // step failed — if ChargePaymentStep itself is the one that
            // failed, paymentId was never set and this is correctly a no-op.
            paymentClient.refund(paymentId);
        }
    }
}
