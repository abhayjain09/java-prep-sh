package com.interviewprep.orders.patterns.behavioral.iterator;

import com.interviewprep.orders.domain.Product;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * CORRECT — an AGGREGATE implementing {@link Iterable}, backed by a custom
 * {@link Iterator} that hides HOW products are stored and filters them
 * according to its own rule (only in-stock products) — the caller never
 * touches the internal {@code List<Product>} or the stock-lookup map
 * directly.
 *
 * WHY A CUSTOM ITERATOR INSTEAD OF JUST RETURNING A FILTERED LIST: this is
 * illustrative of the general Iterator idea (traverse without exposing
 * internal representation), while also demonstrating a genuine advantage
 * over "just call {@code .stream().filter(...)} at each call site" — the
 * filtering RULE ("in stock" means quantity > 0, looked up per SKU) lives
 * in EXACTLY ONE place (this iterator), so it can't drift between callers
 * the way {@link NaiveManualIndexIteration} shows it can. It also means the
 * filtering can lazily skip out-of-stock items one at a time, rather than
 * eagerly building a whole filtered copy up front, which matters for very
 * large catalogs.
 *
 * IMPLEMENTS Iterable<Product> so this class works directly in a for-each
 * loop:
 * <pre>{@code
 * for (Product product : catalog) {
 *     System.out.println(product.sku() + " is in stock");
 * }
 * }</pre>
 */
public class InventoryCatalog implements Iterable<Product> {

    private final List<Product> products = new ArrayList<>();
    private final java.util.Map<String, Integer> stockBySku = new java.util.HashMap<>();

    public void addProduct(Product product, int stock) {
        products.add(product);
        stockBySku.put(product.sku(), stock);
    }

    @Override
    public Iterator<Product> iterator() {
        return new InStockProductIterator();
    }

    /**
     * Private, so external code can only obtain one via {@link #iterator()}
     * — the class's internal storage shape (a List plus a Map) is
     * completely hidden from callers, who only ever see "products, one at a
     * time."
     */
    private final class InStockProductIterator implements Iterator<Product> {
        private int cursor = 0;
        private Product nextInStock;

        InStockProductIterator() {
            advance();
        }

        private void advance() {
            nextInStock = null;
            while (cursor < products.size()) {
                Product candidate = products.get(cursor++);
                if (stockBySku.getOrDefault(candidate.sku(), 0) > 0) {
                    nextInStock = candidate;
                    break;
                }
            }
        }

        @Override
        public boolean hasNext() {
            return nextInStock != null;
        }

        @Override
        public Product next() {
            if (nextInStock == null) {
                throw new NoSuchElementException("No more in-stock products");
            }
            Product result = nextInStock;
            advance();
            return result;
        }
    }
}
