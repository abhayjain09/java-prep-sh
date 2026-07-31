package com.interviewprep.orders.concurrency;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A named stock location with its OWN lock — deliberately a different shape
 * from {@link ReservableInventory}'s implementations, because this class
 * exists purely to build the two-lock deadlock scenario in {@link
 * StockTransferService} and {@link DeadlockDemo}.
 *
 * WHY THE RAW ACCESSORS ARE NAMED "...Unguarded": {@code stockOfUnguarded}/
 * {@code setStockUnguarded}/{@code restockUnguarded} do NOT take {@link
 * #lock()} themselves — the caller (always {@link StockTransferService} in
 * this module) is responsible for holding {@code lock()} before calling
 * them. Naming the danger directly into the method name is a deliberate,
 * cheap defense: a reviewer (or future you) reading {@code
 * warehouse.setStockUnguarded(...)} at a call site with no visible lock
 * acquisition nearby should immediately be suspicious, whereas a
 * innocuous-looking {@code setStock(...)} would not raise the same flag.
 * This is not a substitute for real enforcement (a code reviewer still has
 * to notice), but it is a real, low-cost practice worth adopting in
 * production code that mixes locked and unlocked access paths.
 */
public final class Warehouse {

    private final String id;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Integer> stockBySku = new HashMap<>();

    public Warehouse(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public ReentrantLock lock() {
        return lock;
    }

    public int stockOfUnguarded(String sku) {
        return stockBySku.getOrDefault(sku, 0);
    }

    public void setStockUnguarded(String sku, int quantity) {
        stockBySku.put(sku, quantity);
    }

    public void restockUnguarded(String sku, int quantity) {
        stockBySku.merge(sku, quantity, Integer::sum);
    }

    @Override
    public String toString() {
        return "Warehouse[" + id + "]";
    }
}
