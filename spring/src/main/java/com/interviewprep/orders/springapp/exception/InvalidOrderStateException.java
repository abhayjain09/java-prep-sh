package com.interviewprep.orders.springapp.exception;

/**
 * Thrown by {@code Order.transitionTo()} when a requested {@code OrderStatus}
 * transition is illegal (e.g. DELIVERED -> PENDING). java-basics used a
 * plain {@code IllegalStateException} for this (see its {@code Order.java});
 * this module gives it a dedicated type instead.
 *
 * WHY A DEDICATED TYPE HERE BUT A PLAIN {@code IllegalStateException} WAS FINE
 * IN java-basics: java-basics had no REST layer — the exception only needed
 * to be catchable by a human reading console output. Here, {@code
 * GlobalExceptionHandler} needs to map "illegal order transition" to a
 * SPECIFIC HTTP status (409 Conflict — the request is well-formed but
 * conflicts with the resource's current state) distinctly from other
 * {@code IllegalStateException}s that might occur elsewhere in the app for
 * unrelated reasons and should probably fall through to the generic 500
 * handler instead. A plain {@code IllegalStateException} catch-clause in the
 * handler would over-match: it would also catch illegal-state bugs that
 * have nothing to do with order transitions and misreport them as 409s.
 */
public class InvalidOrderStateException extends RuntimeException {

    public InvalidOrderStateException(String message) {
        super(message);
    }
}
