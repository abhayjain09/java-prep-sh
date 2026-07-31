package com.interviewprep.orders.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * RBAC (Role-Based Access Control), applied concretely to the two Inventory
 * operations from Module 1 ({@code Inventory.restock} and stock lookups) that
 * most obviously need different access levels. This class is the RBAC
 * example the module brief calls for — a real implementation would put
 * {@code @PreAuthorize} directly on the equivalent Spring MVC
 * {@code @RestController} methods in {@code spring/}'s REST layer; it's
 * reproduced standalone here (operating conceptually on a SKU/quantity pair
 * rather than importing Module 1's actual {@code Inventory} class) so this
 * module stays self-contained and compiles without a dependency on another
 * module's build.
 *
 * <h2>How {@code @PreAuthorize} actually works</h2>
 * <p>Spring wraps this bean in a dynamic proxy (via Spring AOP). Before the
 * real method body runs, the proxy evaluates the SpEL (Spring Expression
 * Language) expression in {@code @PreAuthorize} against the current
 * {@code SecurityContext}'s {@code Authentication} (the
 * {@link AppUserPrincipal} that {@link JwtAuthenticationFilter} placed there
 * earlier in the same request). If the expression evaluates to {@code false},
 * the method body never executes at all — Spring throws
 * {@code AccessDeniedException} instead, which a
 * {@code @ControllerAdvice}/{@code @ExceptionHandler} in the REST layer
 * (Module 5's job, or this module's if called directly) translates into an
 * HTTP 403 Forbidden.
 *
 * <h2>{@code hasRole(...)} vs {@code hasAuthority(...)}</h2>
 * <p>{@code hasRole('MANAGER')} is sugar for
 * {@code hasAuthority('ROLE_MANAGER')} — Spring Security prepends
 * {@code ROLE_} automatically. Mixing the two inconsistently across a
 * codebase (some checks say {@code hasRole('MANAGER')}, others say
 * {@code hasAuthority('MANAGER')} without the prefix) is a genuinely common,
 * genuinely confusing bug — the second one silently never matches, because
 * {@link AppUserPrincipal#getAuthorities()} always emits the
 * {@code ROLE_}-prefixed form. This module standardizes on {@code hasRole}/
 * {@code hasAnyRole} everywhere for exactly this reason.
 *
 * <h2>Why method-level security here, not (only) a URL rule</h2>
 * <p>{@link SecurityConfig} could express "only MANAGER can POST to
 * {@code /api/v1/inventory/*}/restock" as a URL-pattern rule instead. Both are
 * legitimate; this module demonstrates method-level security specifically
 * because it composes better as an application grows: the authorization rule
 * travels with the business method itself (visible right next to the code it
 * protects, testable in a plain unit test with a mocked
 * {@code SecurityContext}, safe even if the method is later also invoked from
 * a non-HTTP entry point — an internal batch job, a message-queue consumer —
 * that a URL-based rule would never see or protect).
 */
@Service
public class SecuredInventoryOperations {

    private static final Logger log = LoggerFactory.getLogger(SecuredInventoryOperations.class);

    /**
     * The "manager-only, mutating" operation — conceptually identical to
     * Module 1's {@code Inventory.restock(sku, quantity)}. Only a caller
     * whose JWT carried the {@code MANAGER} (or, per {@code hasAnyRole}
     * design elsewhere, {@code ADMIN}) role can reach this method body at
     * all; a CUSTOMER-only token is rejected with 403 before a single line
     * of restock logic runs.
     */
    @PreAuthorize("hasRole('MANAGER')")
    public void restock(String sku, int quantity) {
        // In the real system this delegates to Module 1's
        // Inventory.restock(sku, quantity). Logged here only to make the
        // RBAC example independently observable/testable.
        log.info("Restocking sku={} by quantity={}", sku, quantity);
    }

    /**
     * The customer-facing, read-only operation — conceptually identical to
     * Module 1's {@code Inventory.stockOf(sku)}. Both MANAGER and CUSTOMER
     * roles may call this; only a completely unauthenticated request (no
     * valid token at all) is rejected — and that rejection actually happens
     * even earlier, at {@link SecurityConfig}'s
     * {@code .anyRequest().authenticated()} rule / the HTTP layer, since this
     * method requires *some* authenticated identity, just not a specific one.
     */
    @PreAuthorize("hasAnyRole('MANAGER', 'CUSTOMER')")
    public int viewStock(String sku) {
        log.info("Viewing stock for sku={}", sku);
        // Placeholder value — a real implementation reads Module 1's
        // Inventory. Returned here only so this method has an observable
        // result for exercises/tests.
        return 42;
    }
}
