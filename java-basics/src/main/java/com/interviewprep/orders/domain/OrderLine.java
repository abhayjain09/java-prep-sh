package com.interviewprep.orders.domain;

import java.math.BigDecimal;

/**
 * One line item within an order: a product plus the quantity ordered.
 *
 * WHY COMPOSITION, NOT "OrderLine extends Product": an order line is not a
 * more specific kind of product — it's a product referenced in the context
 * of a specific order, carrying order-specific data (quantity) that a
 * Product itself knows nothing about. Modeling this as inheritance would
 * violate Liskov substitution (an OrderLine cannot be used everywhere a
 * Product is expected — it has different fields and a different meaning)
 * purely to "reuse" the product fields. Composition ("has a Product") is
 * the correct relationship. This is one of the most common OOP-design
 * interview questions — always justify the "is-a vs has-a" choice.
 *
 * A record is a good fit here too: an OrderLine is immutable once created
 * (changing the quantity means constructing a new OrderLine and replacing
 * it in the order — see Order.addLine/removeLine), and it has no identity
 * beyond its fields.
 */
public record OrderLine(Product product, int quantity) {

    public OrderLine {
        if (product == null) {
            throw new IllegalArgumentException("OrderLine requires a product");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("OrderLine quantity must be positive: " + quantity);
        }
    }

    /**
     * Price is captured from the Product at line-creation time (product.price()).
     * PRODUCTION NOTE: in a real system, prices change over time (promotions,
     * repricing) — an order line should snapshot the price paid, not re-look-up
     * the product's *current* price on every totalAmount() call. This method
     * demonstrates the calculation; Module 5 will show snapshotting the price
     * into the line itself once persistence is introduced, so historical
     * orders are unaffected by later price changes.
     */
    public BigDecimal lineTotal() {
        return product.price().multiply(BigDecimal.valueOf(quantity));
    }
}
