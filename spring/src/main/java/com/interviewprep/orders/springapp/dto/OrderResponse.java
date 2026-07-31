package com.interviewprep.orders.springapp.dto;

import com.interviewprep.orders.springapp.entity.Order;
import com.interviewprep.orders.springapp.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response body for an Order.
 *
 * WHY MAPPING HAPPENS INSIDE {@code OrderService} (WHILE THE PERSISTENCE
 * CONTEXT IS STILL OPEN), NOT IN THE CONTROLLER: {@code order.getLines()}
 * and, transitively, each line's {@code getProduct()} are LAZY
 * associations. With {@code spring.jpa.open-in-view: false} (see
 * application.yml), the Hibernate session closes when the
 * {@code @Transactional} service method returns — so this {@code from(...)}
 * factory must be called from inside that transactional method (or from a
 * repository method that used a JOIN FETCH, see
 * {@code OrderRepository.findByIdWithLines}), never lazily from the
 * controller after the transaction has already committed. This is exactly
 * the discipline open-in-view=false is meant to enforce.
 */
public record OrderResponse(
        Long id,
        String orderNumber,
        Long customerId,
        String customerName,
        OrderStatus status,
        List<OrderLineResponse> lines,
        BigDecimal totalAmount,
        Instant createdAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getCustomer().getId(),
                order.getCustomer().getName(),
                order.getStatus(),
                order.getLines().stream().map(OrderLineResponse::from).toList(),
                order.totalAmount(),
                order.getCreatedAt());
    }
}
