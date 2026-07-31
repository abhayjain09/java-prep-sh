package com.interviewprep.orders.saga;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ILLUSTRATIVE — see SagaContext.java's header note on this LLD's scope
 * and conventions.
 *
 * Tracks which steps have successfully completed within one saga run, in
 * completion order, so they can be compensated in REVERSE order if a
 * later step fails.
 *
 * DIRECT ANALOG: this is the distributed-saga equivalent of the
 * `Deque<OrderLine> reserved` local variable inside java-basics'
 * OrderService.placeOrder():
 *
 *   OrderService.placeOrder()              CompensationRegistry
 *   ----------------------------------     ----------------------------------
 *   Deque<OrderLine> reserved = new        Deque<SagaStep> completedSteps =
 *     ArrayDeque<>();                        new ArrayDeque<>();
 *   reserved.push(line);   // on success   recordCompleted(step); // on success
 *   for (line : reserved)                  for (step : completedSteps)
 *     inventory.release(...);                step.compensate(context);
 *
 * Pulled out into its own class here — rather than staying a private
 * local variable the way `reserved` does in Module 1 — because a real
 * orchestrator needs this list to survive a process crash: it is
 * persisted (alongside the saga id and SagaState) in a saga_instance
 * table, not just held for the duration of one in-memory method call.
 * That persistence is what lets a saga be RESUMED (continue from the
 * next step) or COMPENSATED (roll back what completed) after an
 * orchestrator restart, instead of the in-flight saga simply vanishing
 * with the crashed process the way an uncommitted local variable would.
 */
public class CompensationRegistry {

    private final Deque<SagaStep> completedSteps = new ArrayDeque<>();

    /** Records a step as completed. Call only AFTER step.execute() returns without throwing. */
    public void recordCompleted(SagaStep step) {
        completedSteps.push(step);
    }

    public boolean isEmpty() {
        return completedSteps.isEmpty();
    }

    /**
     * Runs every recorded step's compensate(), most-recently-completed
     * first (iterating an ArrayDeque used as a stack via push() yields
     * exactly that order — head to tail is last-pushed to first-pushed).
     *
     * BEST-EFFORT: one step's compensation failing does not stop the rest
     * from being attempted — see the class Javadoc above and README
     * Section 6's "Common mistakes" for why aborting early here can leave
     * the system in a worse state than a slow, best-effort full rollback.
     */
    public void compensateAll(SagaContext context, CompensationFailureHandler failureHandler) {
        for (SagaStep step : completedSteps) {
            try {
                step.compensate(context);
            } catch (Exception compensationFailure) {
                failureHandler.onCompensationFailure(step, context, compensationFailure);
            }
        }
    }

    @FunctionalInterface
    public interface CompensationFailureHandler {
        /**
         * Called when a step's compensate() throws. Typical production
         * implementation: log at ERROR, emit a metric/alert, and persist
         * the failure for manual operator reconciliation — a failed
         * compensation is exactly the kind of "system is now in a partial
         * state a human needs to look at" event README Section 6 warns
         * a saga must plan for, not silently ignore.
         */
        void onCompensationFailure(SagaStep step, SagaContext context, Exception failure);
    }
}
