package com.interviewprep.orders.saga.orderplacement;

import com.interviewprep.orders.saga.SagaContext;
import com.interviewprep.orders.saga.SagaOrchestrator;
import com.interviewprep.orders.saga.SagaState;
import com.interviewprep.orders.saga.SagaStep;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * ILLUSTRATIVE — see ../SagaContext.java's header note on this LLD's scope
 * and conventions.
 *
 * Wires the five order-placement steps into a SagaOrchestrator and shows
 * how a caller (e.g. a REST controller in the Order Service, reached via
 * the API Gateway per ../../diagrams/hld-microservices.md) invokes it.
 *
 * Compare placeOrder() below directly against java-basics'
 * OrderService.placeOrder(String, List) — same intent ("place this order,
 * all-or-nothing"), same caller-visible contract, entirely different
 * mechanism underneath because the collaborators are now separate
 * services instead of one in-process Inventory object.
 */
public final class OrderPlacementSagaFactory {

    private OrderPlacementSagaFactory() {
        // static factory only
    }

    public static SagaOrchestrator create(
            OrderRepository orderRepository,
            InventoryServiceClient inventoryClient,
            PaymentServiceClient paymentClient,
            ShippingServiceClient shippingClient) {

        List<SagaStep> steps = List.of(
                new CreateOrderStep(orderRepository),
                new ReserveInventoryStep(inventoryClient),
                new ChargePaymentStep(paymentClient),
                new ArrangeShippingStep(shippingClient),
                new ConfirmOrderStep(orderRepository)
        );

        return new SagaOrchestrator(steps);
    }

    /**
     * Example call site — the distributed-system equivalent of
     * `orderService.placeOrder(customer, requestedLines)` from Module 1.
     * A real REST controller would catch SagaStepException here and
     * translate it into an HTTP error response (409/402/etc. depending on
     * which step's failure it wraps), exactly like java-basics' eventual
     * @ControllerAdvice translates InsufficientStockException into a 409.
     */
    public static SagaState placeOrder(
            SagaOrchestrator orchestrator,
            String customerId,
            List<OrderLineDto> orderLines,
            BigDecimal totalAmount) {

        String sagaId = UUID.randomUUID().toString();
        SagaContext context = new SagaContext(sagaId);
        context.put("customerId", customerId);
        context.put("orderLines", orderLines);
        context.put("totalAmount", totalAmount);

        return orchestrator.run(context);
    }
}
