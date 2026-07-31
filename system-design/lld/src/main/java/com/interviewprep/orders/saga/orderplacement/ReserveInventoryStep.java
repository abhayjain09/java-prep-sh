package com.interviewprep.orders.saga.orderplacement;

import com.interviewprep.orders.saga.SagaContext;
import com.interviewprep.orders.saga.SagaStep;

import java.util.List;

/**
 * ILLUSTRATIVE — see ../SagaContext.java's header note on this LLD's scope
 * and conventions.
 *
 * The direct distributed-system analog of java-basics' Inventory.reserve()
 * / Inventory.release(), called over the network at the Inventory service
 * instead of as an in-process method call on a shared object.
 *
 * Context keys used:
 *   reads:  "orderLines" (List<OrderLineDto>)
 *   writes: "inventoryReservationId" (String)
 */
public class ReserveInventoryStep implements SagaStep {

    private final InventoryServiceClient inventoryClient;

    public ReserveInventoryStep(InventoryServiceClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    @Override
    public String name() {
        return "ReserveInventory";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(SagaContext context) throws Exception {
        List<OrderLineDto> lines = context.get("orderLines", List.class);
        String reservationId = inventoryClient.reserve(context.sagaId(), lines);
        context.put("inventoryReservationId", reservationId);
    }

    @Override
    public void compensate(SagaContext context) {
        String reservationId = context.get("inventoryReservationId", String.class);
        if (reservationId != null) {
            // Idempotent by contract (InventoryServiceClient.release Javadoc) —
            // safe even if this compensation is retried after a partial failure.
            inventoryClient.release(reservationId);
        }
    }
}
