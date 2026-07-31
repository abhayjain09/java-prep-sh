package com.interviewprep.orders.patterns.behavioral.chainofresponsibility;

/**
 * Abstract HANDLER: each concrete handler checks ONE thing and, if it
 * passes, forwards the request to the NEXT handler in the chain (if any).
 * The caller only ever talks to the FIRST handler and has no idea how many
 * handlers exist or in what order they run — that's assembled once, at
 * wiring time (see design-patterns/EXPLANATION.md for a wiring example).
 *
 * Throwing (rather than returning a boolean) on failure is a deliberate
 * choice here: it lets any handler short-circuit the rest of the chain
 * simply by not calling {@code next}, and gives the caller one exception
 * type to catch regardless of which check failed — see
 * {@link OrderRejectedException}.
 */
public abstract class OrderValidationHandler {

    private OrderValidationHandler next;

    public OrderValidationHandler setNext(OrderValidationHandler next) {
        this.next = next;
        return next; // returning `next` lets callers chain .setNext().setNext() fluently
    }

    /** Template-ish method: run this handler's own check, then pass along. */
    public final void handle(OrderValidationRequest request) {
        checkSelf(request);
        if (next != null) {
            next.handle(request);
        }
    }

    /** Subclasses implement exactly one check; throw to stop the chain. */
    protected abstract void checkSelf(OrderValidationRequest request);
}
