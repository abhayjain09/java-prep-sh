package com.interviewprep.orders.saga.orderplacement;

import java.util.List;

/**
 * ILLUSTRATIVE — see ../SagaContext.java's header note on this LLD's scope
 * and conventions.
 *
 * Remote client interface for the (now separate) Inventory service — a
 * REST/gRPC client in reality, not a local method call the way
 * java-basics' Inventory.reserve() is. That local-call-vs-network-call
 * distinction is the entire reason ReserveInventoryStep needs a
 * compensation at all: a plain in-process try/catch can't undo a stock
 * decrement that already happened inside a different process's database.
 */
public interface InventoryServiceClient {

    /**
     * Reserves stock for every line. Returns a reservation id used to
     * release the reservation later. Throws on insufficient stock or a
     * remote failure — either is treated by ReserveInventoryStep as this
     * step's failure.
     */
    String reserve(String sagaId, List<OrderLineDto> lines);

    /**
     * Releases a previously made reservation. MUST be idempotent — the
     * orchestrator may call this more than once for the same
     * reservationId after a crash-and-retry (see CompensationRegistry's
     * Javadoc).
     */
    void release(String reservationId);
}
