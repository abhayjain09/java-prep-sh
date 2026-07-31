package com.interviewprep.orders.patterns.behavioral.chainofresponsibility;

import com.interviewprep.orders.domain.OrderLine;

import java.math.BigDecimal;

public class CreditCheckHandler extends OrderValidationHandler {
    @Override
    protected void checkSelf(OrderValidationRequest request) {
        BigDecimal orderTotal = request.lines().stream()
                .map(OrderLine::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal projectedBalance = request.customerCurrentBalance().add(orderTotal);
        if (projectedBalance.compareTo(request.customerCreditLimit()) > 0) {
            throw new OrderRejectedException(
                    "Order would exceed credit limit: projected balance " + projectedBalance
                            + " > limit " + request.customerCreditLimit());
        }
    }
}
