package com.interviewprep.orders.patterns.structural.flyweight;

import com.interviewprep.orders.domain.OrderLine;
import com.interviewprep.orders.domain.Product;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CORRECT — the Flyweight FACTORY: hands out a SHARED {@link Product}
 * instance per distinct sku, constructing a new one only the first time a
 * given sku is seen. Every subsequent request for the same sku returns the
 * SAME object reference.
 *
 * WHY THIS IS SAFE HERE (and wouldn't be for a mutable class): {@code
 * Product} is an immutable record — nothing can mutate a shared instance out
 * from under another order line holding the same reference. Flyweight is
 * ONLY safe for genuinely immutable ("intrinsic") shared state; the
 * order-specific "extrinsic" state (quantity) deliberately stays OUTSIDE the
 * flyweight, on {@code OrderLine}, which is exactly how this codebase was
 * already structured in Module 1 for unrelated reasons (composition over
 * inheritance) — Flyweight just gives that existing structure a name and a
 * caching layer.
 *
 * WHY ConcurrentHashMap: a catalog cache is read (and populated) from
 * multiple threads in any real import/order-processing pipeline;
 * ConcurrentHashMap.computeIfAbsent is atomic per key, so two threads
 * requesting the same brand-new sku for the first time still only construct
 * ONE Product between them (the loser of the race gets the winner's
 * instance back, not a duplicate) — see java-basics/README.md's Inventory
 * race-condition discussion for the same underlying HashMap-vs-
 * ConcurrentHashMap reasoning.
 */
public class ProductFlyweightFactory {

    private final Map<String, Product> cache = new ConcurrentHashMap<>();

    /** Returns the shared Product for {@code sku}, creating it only once. */
    public Product get(String sku, String name, BigDecimal price) {
        return cache.computeIfAbsent(sku, key -> new Product(key, name, price));
    }

    public int distinctProductCount() {
        return cache.size();
    }

    /** Same import shape as the naive version, but sku-sharing aware. */
    public List<OrderLine> buildOrderLines(List<Object[]> rawRows) {
        List<OrderLine> lines = new ArrayList<>();
        for (Object[] row : rawRows) {
            String sku = (String) row[0];
            String name = (String) row[1];
            BigDecimal price = (BigDecimal) row[2];
            int quantity = (int) row[3];

            Product sharedProduct = get(sku, name, price); // reused across every row with this sku
            lines.add(new OrderLine(sharedProduct, quantity));
        }
        return lines;
    }
}
