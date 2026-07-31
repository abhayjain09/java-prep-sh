package com.interviewprep.orders.saga.orderplacement;

import com.interviewprep.orders.saga.SagaContext;
import com.interviewprep.orders.saga.SagaStep;

/**
 * ILLUSTRATIVE — see ../SagaContext.java's header note on this LLD's scope
 * and conventions.
 *
 * The final, terminal step: transitions the order from PENDING to
 * CONFIRMED using the same OrderStatus state-machine rules java-basics'
 * Order.transitionTo() enforces in-process. This step has no
 * compensation of its own — by the time it runs, every other step in the
 * saga has already succeeded, so there is nothing left that a failure
 * here would need to undo except this transition itself, and there is no
 * step after it that could fail and require rolling this one back
 * (see README.md Section 6's compensation table: ConfirmOrder's row is
 * explicitly "terminal step — nothing after it to compensate for").
 */
public class ConfirmOrderStep implements SagaStep {

    private final OrderRepository orderRepository;

    public ConfirmOrderStep(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public String name() {
        return "ConfirmOrder";
    }

    @Override
    public void execute(SagaContext context) throws Exception {
        String orderId = context.get("orderId", String.class);
        orderRepository.transitionTo(orderId, "CONFIRMED");
    }

    @Override
    public void compensate(SagaContext context) {
        // Intentionally a no-op — see class Javadoc. If a future step were
        // ever added after this one, ConfirmOrderStep would need a real
        // compensation (e.g. transition back to a "confirmation reversed"
        // state) at that point.
    }
}
