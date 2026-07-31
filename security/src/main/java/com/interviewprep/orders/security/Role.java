package com.interviewprep.orders.security;

/**
 * The closed set of roles in the Order/Inventory domain, used to drive RBAC
 * (Role-Based Access Control) decisions throughout this module.
 *
 * <p>Why an enum and not a free-text "role" String column: exactly the same
 * argument Module 1's {@code OrderStatus} makes for order states. A closed,
 * compile-time-known set means {@code @PreAuthorize("hasRole('MANGER')")} (a
 * typo) fails loudly in review/tests rather than silently granting access to
 * nobody in production, and every {@code switch} over {@code Role} is checked
 * for exhaustiveness by the compiler if a fourth role is ever added.
 *
 * <p>Mapping onto the Order/Inventory domain this whole repo shares:
 * <ul>
 *   <li>{@link #CUSTOMER} — can place orders and view their own order history.
 *       Cannot restock inventory or view other customers' orders.</li>
 *   <li>{@link #MANAGER} — can restock inventory (the
 *       {@code Inventory.restock(sku, quantity)} operation from Module 1) and
 *       view all orders across all customers, for fulfillment/ops purposes.</li>
 *   <li>{@link #ADMIN} — full control, including user/role management itself
 *       (conceptually — no user-management endpoint is implemented in this
 *       module; it exists here to make the RBAC examples realistic, since a
 *       real system almost always has more than two roles and the interesting
 *       design questions start at three: does MANAGER inherit CUSTOMER's
 *       permissions, or are they disjoint? This module treats them as
 *       disjoint-but-overlapping via explicit {@code hasAnyRole(...)}
 *       expressions rather than a role hierarchy, and explains the trade-off
 *       in README.md's RBAC section.)</li>
 * </ul>
 *
 * <p><b>Common mistake this enum sidesteps:</b> storing roles as a
 * comma-separated String column ("MANAGER,ADMIN") in a database and parsing it
 * ad hoc at every call site. That pattern reappears constantly in legacy
 * codebases and is a frequent root cause of subtle authorization bugs (a typo
 * in one of five places that split the string, an extra space breaking an
 * exact-match comparison). Model roles as a real type as close to the source
 * (the JWT claim, in this module) as possible, and convert once.
 */
public enum Role {
    CUSTOMER,
    MANAGER,
    ADMIN
}
