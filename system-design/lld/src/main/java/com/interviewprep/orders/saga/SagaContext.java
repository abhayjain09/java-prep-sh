package com.interviewprep.orders.saga;

import java.util.HashMap;
import java.util.Map;

/**
 * ILLUSTRATIVE — part of Module 13's Saga Orchestrator LLD. Not compiled or
 * run in this repo (no java/javac in the sandbox this module was written
 * in) — written and re-read carefully for structural correctness, in the
 * same package-and-style convention as java-basics/'s domain classes.
 *
 * Mutable, shared state passed to every step and compensation within a
 * single saga execution. This is the distributed-saga equivalent of the
 * local variables (customer, requestedLines, the constructed Order) that
 * java-basics' OrderService.placeOrder() simply holds as method-local
 * state — because a saga's steps are executed by (conceptually) separate
 * remote calls rather than sequential lines in one method body, that
 * shared state needs an explicit carrier object instead of the JVM stack
 * frame doing it implicitly.
 *
 * PRODUCTION NOTE: a real orchestrator persists this context (or the
 * subset of it needed to resume) alongside the saga's ID in a database
 * table, so a crash mid-saga can be recovered from by reloading the
 * context and resuming or compensating from the last known step — a saga
 * that only lives in memory does not survive an orchestrator restart.
 */
public final class SagaContext {

    private final String sagaId;
    private final Map<String, Object> attributes = new HashMap<>();

    public SagaContext(String sagaId) {
        if (sagaId == null || sagaId.isBlank()) {
            throw new IllegalArgumentException("sagaId must not be blank");
        }
        this.sagaId = sagaId;
    }

    public String sagaId() {
        return sagaId;
    }

    /** Stores a value produced by one step for use by a later step or its own compensation. */
    public void put(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * Retrieves a previously stored value. Returns null if absent — callers
     * (steps/compensations) are responsible for null-checking, exactly like
     * java-basics' Inventory.stockOf() treats "absent" as a normal case
     * rather than throwing.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        return (T) attributes.get(key);
    }

    public boolean has(String key) {
        return attributes.containsKey(key);
    }
}
