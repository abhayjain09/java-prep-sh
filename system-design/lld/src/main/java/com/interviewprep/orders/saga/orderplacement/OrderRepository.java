package com.interviewprep.orders.saga.orderplacement;

import java.math.BigDecimal;
import java.util.List;

/**
 * ILLUSTRATIVE — see ../SagaContext.java's header note on this LLD's scope
 * and conventions.
 *
 * Unlike InventoryServiceClient/PaymentServiceClient/ShippingServiceClient
 * (remote calls to OTHER services), this is a LOCAL persistence interface:
 * per the HLD (../../diagrams/hld-microservices.md), the SagaOrchestrator
 * runs inside the Order Service itself, which owns the Order/OrderStatus
 * domain objects and their database directly — exactly like java-basics'
 * OrderService owns Order construction and transitionTo() calls in-process
 * today. CreateOrderStep and ConfirmOrderStep therefore call this local
 * repository, not a network client; only steps that cross into a genuinely
 * different service (Inventory, Payment, Shipping) need the *ServiceClient
 * interfaces and their idempotency/compensation concerns.
 */
public interface OrderRepository {

    /** Creates and persists a new Order in PENDING status. Returns the generated orderId. */
    String createPendingOrder(String sagaId, String customerId, List<OrderLineDto> lines, BigDecimal totalAmount);

    /**
     * Transitions the order to the given status, delegating the legality
     * check to the same OrderStatus.canTransitionTo() rule java-basics'
     * Order.transitionTo() uses — a saga step is not exempt from the
     * domain's own state-machine invariants just because it's being
     * called from an orchestrator instead of directly from application code.
     */
    void transitionTo(String orderId, String targetStatus);
}
