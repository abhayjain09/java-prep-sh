package com.interviewprep.orders.saga.orderplacement;

import com.interviewprep.orders.saga.SagaContext;
import com.interviewprep.orders.saga.SagaStep;

import java.util.List;

/**
 * ILLUSTRATIVE — see ../SagaContext.java's header note on this LLD's scope
 * and conventions.
 *
 * Context keys used:
 *   reads:  "orderId" (String), "orderLines" (List<OrderLineDto>)
 *   writes: "shipmentId" (String)
 */
public class ArrangeShippingStep implements SagaStep {

    private final ShippingServiceClient shippingClient;

    public ArrangeShippingStep(ShippingServiceClient shippingClient) {
        this.shippingClient = shippingClient;
    }

    @Override
    public String name() {
        return "ArrangeShipping";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(SagaContext context) throws Exception {
        String orderId = context.get("orderId", String.class);
        List<OrderLineDto> lines = context.get("orderLines", List.class);
        String shipmentId = shippingClient.scheduleShipment(context.sagaId(), orderId, lines);
        context.put("shipmentId", shipmentId);
    }

    @Override
    public void compensate(SagaContext context) {
        String shipmentId = context.get("shipmentId", String.class);
        if (shipmentId != null) {
            shippingClient.cancelShipment(shipmentId);
        }
    }
}
