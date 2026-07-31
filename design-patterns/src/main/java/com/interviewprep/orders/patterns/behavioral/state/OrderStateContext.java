package com.interviewprep.orders.patterns.behavioral.state;

import java.math.BigDecimal;

/**
 * CORRECT — the State pattern's CONTEXT: holds the CURRENT {@link OrderState}
 * and delegates every status-dependent question/action to it, rather than
 * asking "what status am I in?" and branching itself.
 *
 * EVERYTHING about one status now lives in ONE class (e.g. all of
 * {@code ShippedState}'s rules together), instead of being scattered across
 * multiple switch statements as in {@link NaiveStatusConditionals}. Adding a
 * new per-status rule means adding ONE method to the {@link OrderState}
 * interface and implementing it once per state class — the compiler forces
 * every existing state class to handle it (a missing @Override-able method
 * fails to compile), which is a similar exhaustiveness guarantee to the
 * enum's switch expression, but now covering an arbitrarily large, growing
 * set of per-status behaviors instead of just transition legality.
 *
 * ============================================================================
 * WHEN TO ACTUALLY USE THIS OVER java-basics' OrderStatus ENUM — DIRECT ANSWER
 * ============================================================================
 * Use the plain enum (Module 1's approach) when: the set of statuses is
 * small and stable, and the only real "behavior" is a transition-legality
 * lookup table (exactly OrderStatus.canTransitionTo's job).
 *
 * Graduate to full State (this package) when EITHER:
 *  - The number of DISTINCT per-status BEHAVIORS grows past one or two
 *    (fee calculation, editability, allowed actions, applicable SLAs,
 *    required approvals — this example already has three; a real order
 *    system commonly needs five or more), OR
 *  - Per-status behavior needs to be INJECTED/CONFIGURED at runtime (e.g. a
 *    state's cancellation fee percentage varies by merchant tier or comes
 *    from a database) — an interface implementation can take constructor
 *    parameters; an enum constant's behavior is fixed at compile time.
 *
 * Both are legitimate, well-understood trade-offs — this is a genuinely
 * common senior-interview question ("would you use an enum or the State
 * pattern here, and why") with no universally "correct" answer, only a
 * correctly-reasoned one.
 */
public class OrderStateContext {

    private OrderState currentState;

    public OrderStateContext() {
        this.currentState = new PendingState(); // every order starts PENDING
    }

    public void progress() {
        currentState = currentState.next();
    }

    public BigDecimal cancel() {
        BigDecimal fee = currentState.cancellationFeePercentage();
        currentState = new CancelledState();
        return fee;
    }

    public boolean isEditable() {
        return currentState.isEditable();
    }

    public String currentStateName() {
        return currentState.name();
    }
}
