package com.interviewprep.orders.patterns.structural.flyweight;

import com.interviewprep.orders.domain.OrderLine;
import com.interviewprep.orders.domain.Product;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * WRONG — constructs a brand-new {@link Product} object for every order
 * line, even when the SAME sku/name/price combination has already been
 * built (and could safely be reused) for another line elsewhere in the
 * system.
 *
 * WHY THIS MATTERS AT SCALE: {@code Product} is immutable (a record — see
 * java-basics) and has NO order-specific state (that's what {@code
 * OrderLine.quantity} is for) — every field of a Product with sku "SKU-1" is
 * identical to every other Product with sku "SKU-1". A high-volume order
 * processing pipeline (think a Black-Friday-scale batch import, or a system
 * processing millions of order lines a day) that constructs a fresh Product
 * per line for a catalog of only a few thousand distinct SKUs wastes memory
 * proportional to LINE COUNT instead of proportional to CATALOG SIZE — for
 * a catalog of 5,000 SKUs and 10 million order lines referencing them,
 * that's 10 million redundant Product objects instead of 5,000 shared ones.
 *
 * This is exactly the scenario the Flyweight pattern targets: share
 * IMMUTABLE, reusable ("intrinsic") state across many contexts instead of
 * duplicating it per use. See {@link ProductFlyweightFactory}.
 */
public class NaiveOrderLineCreation {

    /**
     * Simulates importing a large batch of raw (sku, name, price, quantity)
     * rows and constructing an OrderLine per row — a brand-new Product
     * object every time, even for rows repeating the same sku.
     */
    public List<OrderLine> buildOrderLines(List<Object[]> rawRows) {
        List<OrderLine> lines = new ArrayList<>();
        for (Object[] row : rawRows) {
            String sku = (String) row[0];
            String name = (String) row[1];
            BigDecimal price = (BigDecimal) row[2];
            int quantity = (int) row[3];

            // A fresh Product every single row, regardless of whether an
            // identical one (same sku) already exists from an earlier row.
            Product product = new Product(sku, name, price);
            lines.add(new OrderLine(product, quantity));
        }
        return lines;
    }
}
