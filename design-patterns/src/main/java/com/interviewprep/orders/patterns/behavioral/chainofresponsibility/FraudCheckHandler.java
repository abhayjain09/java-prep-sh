package com.interviewprep.orders.patterns.behavioral.chainofresponsibility;

public class FraudCheckHandler extends OrderValidationHandler {
    @Override
    protected void checkSelf(OrderValidationRequest request) {
        if (request.flaggedForFraudReview()) {
            throw new OrderRejectedException(
                    "Customer " + request.customer().id() + " is flagged for manual fraud review");
        }
    }
}
