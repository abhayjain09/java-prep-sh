package com.interviewprep.orders.saga;

import java.util.List;

/**
 * ILLUSTRATIVE — see SagaContext.java's header note on this LLD's scope
 * and conventions.
 *
 * Coordinates execution of an ordered list of SagaSteps, compensating
 * every completed step (in reverse order, via CompensationRegistry) if
 * any step fails.
 *
 * THIS IS THE DIRECT, EXPLICIT GENERALIZATION of java-basics'
 * OrderService.placeOrder():
 *
 *   OrderService.placeOrder() (Module 1, single process, single collaborator)
 *   ---------------------------------------------------------------------
 *   Deque<OrderLine> reserved = new ArrayDeque<>();
 *   try {
 *       for (OrderLine line : requestedLines) {
 *           inventory.reserve(line.product().sku(), line.quantity());
 *           reserved.push(line);
 *       }
 *   } catch (InsufficientStockException e) {
 *       for (OrderLine line : reserved) {
 *           inventory.release(line.product().sku(), line.quantity());
 *       }
 *       throw e;
 *   }
 *
 *   SagaOrchestrator.run() (Module 13, N independently-deployed services)
 *   ---------------------------------------------------------------------
 *   CompensationRegistry registry = new CompensationRegistry();
 *   for (SagaStep step : steps) {
 *       try {
 *           step.execute(context);
 *           registry.recordCompleted(step);
 *       } catch (Exception e) {
 *           registry.compensateAll(context, ...);
 *           throw new SagaStepException(step.name(), e);
 *       }
 *   }
 *
 * Same shape — sequential steps, remember what succeeded, undo it in
 * reverse on failure. What makes the distributed version genuinely harder
 * (see README.md Section 6 for the full discussion):
 *  1. Each "step" is a network call to a different service/database
 *     instead of an in-process method call — it can fail in ways a local
 *     call never does (timeout, the call succeeding but the response
 *     being lost, the remote process crashing mid-request).
 *  2. There is no shared ACID transaction across services, so this
 *     orchestrator's own progress (which steps completed) must itself be
 *     durable enough to survive a crash — see CompensationRegistry's
 *     Javadoc.
 *  3. Compensation is not a guaranteed-clean undo the way rolling back a
 *     local variable's effects is — ReleaseInventory or RefundPayment can
 *     themselves fail, must be idempotent, and a real orchestrator must
 *     have a plan (retry, dead-letter queue, manual ops review) for when
 *     they do.
 */
public class SagaOrchestrator {

    private final List<SagaStep> steps;

    public SagaOrchestrator(List<SagaStep> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be null or empty");
        }
        this.steps = List.copyOf(steps);
    }

    /**
     * Runs every step in order. Returns SagaState.COMPLETED if every step
     * succeeded. If any step fails, compensates every previously-completed
     * step (best-effort, reverse order) and throws SagaStepException
     * identifying which step triggered the rollback and why.
     *
     * PRODUCTION NOTE: a real implementation persists `state` and the
     * registry's contents to a saga_instance table after every step
     * transition (not just held in local variables as shown here for
     * clarity), so a crash between steps can be recovered by reloading
     * the saga's last known state and resuming compensation or forward
     * progress rather than losing track of an in-flight saga entirely.
     */
    public SagaState run(SagaContext context) {
        CompensationRegistry registry = new CompensationRegistry();
        SagaState state = SagaState.STARTED;

        for (SagaStep step : steps) {
            state = SagaState.IN_PROGRESS;
            try {
                step.execute(context);
                registry.recordCompleted(step);
            } catch (Exception stepFailure) {
                state = SagaState.COMPENSATING;
                registry.compensateAll(context, this::logCompensationFailure);
                state = SagaState.FAILED;
                throw new SagaStepException(step.name(), stepFailure);
            }
        }

        return SagaState.COMPLETED;
    }

    /**
     * Default best-effort compensation-failure handling. A real system
     * replaces this with structured logging + metrics/alerting + a
     * persisted record for manual reconciliation — see
     * CompensationRegistry.CompensationFailureHandler's Javadoc.
     */
    private void logCompensationFailure(SagaStep step, SagaContext context, Exception failure) {
        System.err.println("[saga=%s] compensation FAILED for step '%s': %s"
                .formatted(context.sagaId(), step.name(), failure.getMessage()));
    }
}
