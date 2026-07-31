package com.interviewprep.orders.patterns.behavioral.chainofresponsibility;

import com.interviewprep.orders.domain.OrderLine;

public class StockCheckHandler extends OrderValidationHandler {
    @Override
    protected void checkSelf(OrderValidationRequest request) {
        for (OrderLine line : request.lines()) {
            int available = request.inventory().stockOf(line.product().sku());
            if (available < line.quantity()) {
                throw new OrderRejectedException(
                        "Insufficient stock for " + line.product().sku() + ": requested "
                                + line.quantity() + ", available " + available);
            }
        }
    }
}
