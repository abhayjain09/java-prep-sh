package com.interviewprep.orders.patterns.structural.proxy;

/**
 * WRONG — every caller that wants to restock inventory is trusted to
 * remember, ON ITS OWN, to check the current user's role first. There is no
 * single choke point enforcing "only WAREHOUSE_ADMIN may restock."
 *
 * WHY THIS IS A PROBLEM:
 * 1. ONE FORGOTTEN CHECK = A SECURITY HOLE: this class has TWO restock call
 *    sites; {@link #restockFromAdminTool} remembers the check,
 *    {@link #restockFromBatchJob} does not (a very plausible real-world
 *    scenario — the batch job was written later, by someone unaware of the
 *    security requirement, or under time pressure). The bug compiles fine,
 *    passes casual testing, and only surfaces when a report shows stock was
 *    manipulated by a non-admin service account.
 * 2. NOT AUDITABLE FROM ONE PLACE: to verify "is restock() ever reachable
 *    without a role check," a reviewer must find and inspect EVERY call site
 *    in the codebase, forever, as new callers are added.
 *
 * See {@link SecuredInventoryProxy}: the access check lives in exactly one
 * place, wrapping the real subject, so EVERY caller — no matter how many
 * exist or will exist — is protected automatically, with no way to
 * "forget" the check.
 */
public class NaiveUncontrolledAccess {

    private final InventoryOperations inventory;

    public NaiveUncontrolledAccess(InventoryOperations inventory) {
        this.inventory = inventory;
    }

    public void restockFromAdminTool(Role callerRole, String sku, int quantity) {
        if (callerRole != Role.WAREHOUSE_ADMIN) {
            throw new SecurityException("Only WAREHOUSE_ADMIN may restock inventory");
        }
        inventory.restock(sku, quantity);
    }

    /**
     * BUG: this call site forgot the role check entirely — restocking is
     * reachable here for ANY caller, including READ_ONLY_REPORTING.
     */
    public void restockFromBatchJob(Role callerRole, String sku, int quantity) {
        inventory.restock(sku, quantity); // <-- no check!
    }
}
