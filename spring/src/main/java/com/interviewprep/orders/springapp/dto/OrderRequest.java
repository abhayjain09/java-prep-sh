package com.interviewprep.orders.springapp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for placing an order.
 *
 * WHY {@code @Valid} ON THE NESTED LIST: Bean Validation does NOT cascade
 * into nested objects/collections by default — without {@code @Valid} here,
 * each {@code OrderLineRequest}'s own {@code @NotNull}/{@code @Positive}
 * constraints would be silently skipped, and an order with a null
 * {@code productId} would sail past validation only to fail later with a
 * much less clear NullPointerException or SQL error deep in the service
 * layer. Forgetting this {@code @Valid} on a nested collection is one of
 * the most common real-world Bean Validation bugs.
 */
public record OrderRequest(

        @NotNull(message = "customerId is required")
        Long customerId,

        @NotEmpty(message = "an order must contain at least one line")
        @Valid
        List<OrderLineRequest> lines
) {
}
