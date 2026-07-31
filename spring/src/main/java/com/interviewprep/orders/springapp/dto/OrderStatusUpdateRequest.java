package com.interviewprep.orders.springapp.dto;

import com.interviewprep.orders.springapp.entity.OrderStatus;
import jakarta.validation.constraints.NotNull;

/** Request body for {@code PATCH /api/v1/orders/{id}/status}. */
public record OrderStatusUpdateRequest(

        @NotNull(message = "status is required")
        OrderStatus status
) {
}
