package com.interviewprep.orders.springapp.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** One requested line within an {@code OrderRequest}. */
public record OrderLineRequest(

        @NotNull(message = "productId is required")
        Long productId,

        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be positive")
        Integer quantity
) {
}
