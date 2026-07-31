package com.interviewprep.orders.patterns.behavioral.iterator;

import com.interviewprep.orders.domain.Product;

import java.util.List;
import java.util.Map;

/**
 * WRONG — client code reaches directly into the catalog's internal storage
 * (a raw {@code List<Product>} plus a separate stock map, both exposed via
 * plain getters) and re-implements the "only in-stock products" filtering
 * logic itself, using manual index-based iteration.
 *
 * WHY THIS IS A PROBLEM:
 * 1. FILTERING LOGIC IS DUPLICATED PER CALLER: every place that needs
 *    "in-stock products only" re-writes the same {@code stockOf(sku) > 0}
 *    check. If the definition of "in stock" changes (e.g. "in stock" should
 *    also exclude products flagged for recall), every duplicated call site
 *    needs to be found and updated — a correctness risk directly comparable
 *    to the naive discount/payment "type" if/else chains seen elsewhere in
 *    this module.
 * 2. BREAKS ENCAPSULATION: this class needs to know the catalog is backed
 *    by a {@code List} AND a separate {@code Map} for stock — an
 *    implementation detail that should be free to change (e.g. to a single
 *    combined data structure) without breaking every caller that iterates
 *    "the naive way."
 * 3. INDEX-BASED BUGS: manual {@code for (int i = 0; ...)} loops over one
 *    collection while looking values up in another, correlated-by-position
 *    collection, is a classic source of off-by-one and
 *    IndexOutOfBoundsException bugs, especially once the two collections
 *    can get out of sync in size.
 *
 * See {@link InventoryCatalog}: the filtering rule lives in ONE place (the
 * iterator), and callers just for-each over the catalog with no knowledge
 * of how it's stored internally.
 */
public class NaiveManualIndexIteration {

    /**
     * Simulates the "catalog" as two raw, exposed collections instead of
     * one encapsulated Iterable — this is what a poorly-designed
     * getAllProducts()/getStockMap() pair of getters looks like from the
     * caller's side.
     */
    public void printInStockProducts(List<Product> allProducts, Map<String, Integer> stockBySku) {
        for (int i = 0; i < allProducts.size(); i++) {
            Product product = allProducts.get(i);
            // Re-implements the exact filtering rule InventoryCatalog's
            // iterator already encapsulates — duplicated here, and again at
            // every other call site that needs "in stock only."
            Integer stock = stockBySku.get(product.sku());
            if (stock != null && stock > 0) {
                System.out.println(product.sku() + " is in stock");
            }
        }
    }
}
