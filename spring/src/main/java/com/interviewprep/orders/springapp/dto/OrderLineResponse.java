package com.interviewprep.orders.springapp.dto;

import com.interviewprep.orders.springapp.entity.OrderLine;

import java.math.BigDecimal;

public record OrderLineResponse(
        String productSku,
        String productName,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal
) {
    public static OrderLineResponse from(OrderLine line) {
        return new OrderLineResponse(
                line.getProduct().getSku(),
                line.getProduct().getName(),
                line.getQuantity(),
                line.getUnitPriceAtOrderTime(),
                line.lineTotal());
    }
}
