package com.interviewprep.orders.patterns.structural.proxy;

import com.interviewprep.orders.domain.Inventory;

/**
 * The REAL SUBJECT: adapts the java-basics {@code Inventory} domain class
 * (which has no interface of its own — it wasn't designed with Proxy in
 * mind, matching how most real domain classes are written) onto
 * {@link InventoryOperations} so a proxy can stand in for it.
 */
public class RealInventory implements InventoryOperations {

    private final Inventory inventory;

    public RealInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public void restock(String sku, int quantity) {
        inventory.restock(sku, quantity);
    }

    @Override
    public void reserve(String sku, int quantity) {
        inventory.reserve(sku, quantity);
    }

    @Override
    public void release(String sku, int quantity) {
        inventory.release(sku, quantity);
    }

    @Override
    public int stockOf(String sku) {
        return inventory.stockOf(sku);
    }
}
