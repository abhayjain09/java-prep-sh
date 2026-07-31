package com.interviewprep.orders.saga;

/**
 * ILLUSTRATIVE — see SagaContext.java's header note on this LLD's scope
 * and conventions.
 *
 * Thrown by SagaOrchestrator.run() once a step fails AND compensation of
 * every previously-completed step has been attempted. Unchecked, for the
 * same reason java-basics' InsufficientStockException is unchecked (see
 * that class's Javadoc and java-basics/README.md's Exception Handling
 * section): this is a business-level saga failure with one natural
 * handling point (the caller of run(), e.g. an OrderCommandService
 * translating it into an HTTP error response), not something every
 * intermediate layer should be forced to declare.
 *
 * getCause() always holds the ORIGINAL failure from the step that
 * triggered compensation — never swallowed or replaced, per the same
 * "never lose the cause" rule java-basics' OrderService.placeOrder()
 * follows with `throw e;` rather than wrapping and discarding it.
 */
public class SagaStepException extends RuntimeException {

    private final String stepName;

    public SagaStepException(String stepName, Throwable cause) {
        super("Saga step '%s' failed: %s".formatted(stepName, cause.getMessage()), cause);
        this.stepName = stepName;
    }

    public String stepName() {
        return stepName;
    }
}
