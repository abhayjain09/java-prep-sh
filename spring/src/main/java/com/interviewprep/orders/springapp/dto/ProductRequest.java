package com.interviewprep.orders.springapp.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Request body for creating a Product. See {@code CustomerRequest}'s
 * Javadoc for why this is a DTO distinct from the {@code Product} entity. */
public record ProductRequest(

        @NotBlank(message = "sku must not be blank")
        String sku,

        @NotBlank(message = "name must not be blank")
        String name,

        @NotNull(message = "price is required")
        @DecimalMin(value = "0.0", inclusive = true, message = "price must be zero or positive")
        @Digits(integer = 17, fraction = 2, message = "price must have at most 2 decimal places")
        BigDecimal price,

        @NotNull(message = "initialStockQuantity is required")
        @Min(value = 0, message = "initialStockQuantity must be zero or positive")
        Integer initialStockQuantity
) {
}
