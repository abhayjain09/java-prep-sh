package com.interviewprep.orders.saga.orderplacement;

import java.util.List;

/**
 * ILLUSTRATIVE — see ../SagaContext.java's header note on this LLD's scope
 * and conventions.
 *
 * Remote client interface for the Shipping / Fulfillment service.
 */
public interface ShippingServiceClient {

    /** Schedules a shipment. Returns a shipmentId used to cancel it later. */
    String scheduleShipment(String sagaId, String orderId, List<OrderLineDto> lines);

    /** Cancels a previously scheduled shipment. MUST be idempotent. */
    void cancelShipment(String shipmentId);
}
