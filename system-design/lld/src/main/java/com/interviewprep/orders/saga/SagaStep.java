package com.interviewprep.orders.saga;

/**
 * ILLUSTRATIVE — see SagaContext.java's header note on this LLD's scope
 * and conventions.
 *
 * One step in a saga: a forward action plus its compensation. Every
 * concrete implementation (see saga/orderplacement/) represents a call to
 * one collaborating service — the distributed equivalent of one
 * `inventory.reserve(...)` call inside java-basics'
 * OrderService.placeOrder()'s loop body.
 *
 * CONTRACT EACH IMPLEMENTATION MUST HONOR:
 *  - execute() performs the forward action and stores anything compensate()
 *    will need (e.g. a reservation/payment/shipment id) into the
 *    SagaContext via context.put(...).
 *  - compensate() MUST be idempotent — the orchestrator may invoke it more
 *    than once for the same saga instance after a crash-and-resume, and
 *    must never throw for "there was nothing to compensate" (that's a
 *    valid, expected case if execute() never got far enough to need
 *    undoing — check the context for the expected key and no-op if absent).
 *  - compensate() should not throw for retryable failures without its own
 *    bounded retry — but if it does throw, the orchestrator's
 *    CompensationRegistry treats that as best-effort-failed and continues
 *    compensating the remaining steps rather than aborting (see
 *    SagaOrchestrator.java).
 */
public interface SagaStep {

    /** A short, stable, human-readable identifier used in logs, metrics, and SagaStepException messages. */
    String name();

    /**
     * Performs the forward action. Any checked or unchecked exception is
     * treated by SagaOrchestrator as this step's failure and triggers
     * compensation of every step that completed before it.
     */
    void execute(SagaContext context) throws Exception;

    /**
     * Undoes this step's effect. Called only for steps whose execute()
     * previously completed successfully, in reverse order of completion.
     * Implementations should catch and handle their own transient errors
     * where possible; anything that escapes is logged and does not stop
     * the rest of the rollback (see CompensationRegistry).
     */
    void compensate(SagaContext context);
}
