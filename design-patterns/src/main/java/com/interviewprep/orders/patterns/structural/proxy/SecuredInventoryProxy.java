package com.interviewprep.orders.patterns.structural.proxy;

/**
 * CORRECT — a PROTECTION PROXY: implements the same
 * {@link InventoryOperations} interface as the real subject, and stands in
 * for it everywhere, adding an access check BEFORE ever delegating to the
 * real {@link RealInventory}. Every caller goes through this class (by
 * being handed a proxy instance instead of the real one at wiring time) and
 * gets the same enforcement — there is no call path that reaches the real
 * subject's {@code restock()} without passing through the check here first.
 *
 * FIXES {@link NaiveUncontrolledAccess}'s BUG STRUCTURALLY, not just by
 * convention: a new caller (e.g. a future batch job) that's handed this
 * proxy CANNOT forget the check, because the check isn't the caller's
 * responsibility at all anymore.
 *
 * OTHER PROXY VARIANTS WORTH KNOWING FOR INTERVIEWS (not implemented here,
 * to keep this example focused — see design-patterns/INTERVIEW.md):
 *  - VIRTUAL PROXY: defers creating an expensive real subject until it's
 *    actually used (e.g. an InventoryProxy that doesn't connect to a slow
 *    remote warehouse system until the first real call is made).
 *  - REMOTE PROXY: represents an object that lives in a different process/
 *    machine (this is what RMI stubs, and gRPC/Feign client proxies in
 *    Spring, generate for you automatically).
 *  - CACHING PROXY: memoizes {@code stockOf()} results for a short TTL to
 *    avoid hammering a slow backing store (previewed here conceptually;
 *    full treatment with Spring Cache / Redis is a later module).
 * All of these share the same shape: same interface as the real subject,
 * additional behavior wrapped around delegation, caller none the wiser.
 */
public class SecuredInventoryProxy implements InventoryOperations {

    private final InventoryOperations realInventory;
    private final Role callerRole;

    public SecuredInventoryProxy(InventoryOperations realInventory, Role callerRole) {
        this.realInventory = realInventory;
        this.callerRole = callerRole;
    }

    @Override
    public void restock(String sku, int quantity) {
        requireRole(Role.WAREHOUSE_ADMIN);
        realInventory.restock(sku, quantity);
    }

    @Override
    public void reserve(String sku, int quantity) {
        // Reserving stock (as part of a normal checkout) is allowed for any
        // authenticated role in this simplified example — only the
        // destructive/administrative restock() operation is restricted.
        realInventory.reserve(sku, quantity);
    }

    @Override
    public void release(String sku, int quantity) {
        realInventory.release(sku, quantity);
    }

    @Override
    public int stockOf(String sku) {
        return realInventory.stockOf(sku);
    }

    private void requireRole(Role required) {
        if (callerRole != required) {
            throw new SecurityException(
                    "Caller role " + callerRole + " is not permitted to perform this operation (requires " + required + ")");
        }
    }
}
