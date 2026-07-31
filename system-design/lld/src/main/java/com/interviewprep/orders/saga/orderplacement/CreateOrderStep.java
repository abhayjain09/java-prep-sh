package com.interviewprep.orders.saga.orderplacement;

import com.interviewprep.orders.saga.SagaContext;
import com.interviewprep.orders.saga.SagaStep;

import java.math.BigDecimal;
import java.util.List;

/**
 * ILLUSTRATIVE — see ../SagaContext.java's header note on this LLD's scope
 * and conventions.
 *
 * First step of the order-placement saga: creates the Order in PENDING
 * status. Its compensation transitions the order to CANCELLED if any
 * later step fails — the saga-level equivalent of never having placed the
 * order at all, from the customer's perspective, while still keeping an
 * auditable record that an attempt was made and why it didn't complete.
 *
 * Context keys used (documented here since this is the first step and
 * establishes them for every step after it):
 *   reads:  "customerId" (String), "orderLines" (List<OrderLineDto>), "totalAmount" (BigDecimal)
 *   writes: "orderId" (String)
 */
public class CreateOrderStep implements SagaStep {

    private final OrderRepository orderRepository;

    public CreateOrderStep(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public String name() {
        return "CreateOrder";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(SagaContext context) throws Exception {
        String customerId = context.get("customerId", String.class);
        List<OrderLineDto> lines = context.get("orderLines", List.class);
        BigDecimal totalAmount = context.get("totalAmount", BigDecimal.class);

        String orderId = orderRepository.createPendingOrder(context.sagaId(), customerId, lines, totalAmount);
        context.put("orderId", orderId);
    }

    @Override
    public void compensate(SagaContext context) {
        String orderId = context.get("orderId", String.class);
        if (orderId != null) {
            orderRepository.transitionTo(orderId, "CANCELLED");
        }
    }
}
