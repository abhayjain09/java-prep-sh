package com.interviewprep.orders.saga;

/**
 * ILLUSTRATIVE — see SagaContext.java's header note on this LLD's scope
 * and conventions.
 *
 * The lifecycle states of one saga instance. Mirrors java-basics'
 * OrderStatus in spirit (a small, closed enum expressing a state machine)
 * but for the saga's own execution progress, not the Order's business
 * status — these are related but distinct state machines. An order can be
 * PENDING while its saga is IN_PROGRESS; an order becomes CANCELLED as a
 * *result* of its saga reaching FAILED and successfully compensating.
 */
public enum SagaState {
    /** Saga instance created, no steps attempted yet. */
    STARTED,
    /** At least one step has executed successfully; more remain. */
    IN_PROGRESS,
    /** Every step completed successfully. Terminal, successful outcome. */
    COMPLETED,
    /** A step failed; compensations for prior completed steps are running. */
    COMPENSATING,
    /** Every recorded compensation was attempted. Terminal, rolled-back outcome. */
    FAILED
}
