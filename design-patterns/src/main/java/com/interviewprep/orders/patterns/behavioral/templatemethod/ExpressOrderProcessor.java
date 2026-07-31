package com.interviewprep.orders.patterns.behavioral.templatemethod;

import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderLine;

import java.util.List;

public class ExpressOrderProcessor extends OrderProcessorTemplate {

    public ExpressOrderProcessor(Inventory inventory) {
        super(inventory);
    }

    @Override
    protected void chargeShipping(List<OrderLine> lines) {
        System.out.println("Charging express shipping surcharge");
    }

    @Override
    protected void notifyCustomer(Order order) {
        System.out.println("Notified customer: express delivery in 1-2 days for " + order.id());
    }
}
