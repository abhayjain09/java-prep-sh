package com.interviewprep.orders.springapp.dto;

import com.interviewprep.orders.springapp.entity.Product;

import java.math.BigDecimal;

/** Response body for a Product. A plain record — no HATEOAS links here on
 * purpose, to keep exactly one endpoint (Customer) demonstrating that
 * pattern rather than mechanically applying it everywhere (see
 * spring/README.md's HATEOAS section on how rarely it's used in practice). */
public record ProductResponse(
        Long id,
        String sku,
        String name,
        BigDecimal price,
        int stockQuantity
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getPrice(),
                product.getStockQuantity());
    }
}
