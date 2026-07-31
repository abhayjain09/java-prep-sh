package com.interviewprep.orders.domain;

import java.math.BigDecimal;

/**
 * A sellable product identified by SKU.
 *
 * WHY BigDecimal AND NOT double/float FOR price: double/float use binary
 * floating point, which cannot represent most decimal fractions exactly
 * (0.1 + 0.2 != 0.3 in binary floating point). For money, that rounding
 * error compounds across millions of transactions into real accounting
 * discrepancies. BigDecimal represents decimals exactly and is the
 * standard choice for money in Java — the small performance cost versus
 * primitives is irrelevant next to the correctness requirement.
 * This is a very common senior-interview trip-up question.
 */
public record Product(String sku, String name, BigDecimal price) {

    public Product {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("Product sku must not be blank");
        }
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("Product price must be zero or positive: " + price);
        }
    }
}
