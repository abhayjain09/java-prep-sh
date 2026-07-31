package com.interviewprep.orders.patterns.structural.proxy;

/**
 * A common interface implemented by BOTH the real {@code Inventory}
 * (adapted below) and its {@link SecuredInventoryProxy} — this is what lets
 * the proxy be substituted for the real subject anywhere the interface is
 * expected, with callers unable to tell the difference.
 */
public interface InventoryOperations {
    void restock(String sku, int quantity);
    void reserve(String sku, int quantity);
    void release(String sku, int quantity);
    int stockOf(String sku);
}
