package com.interviewprep.orders.patterns.behavioral.templatemethod;

import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderLine;

import java.util.List;

public class StandardOrderProcessor extends OrderProcessorTemplate {

    public StandardOrderProcessor(Inventory inventory) {
        super(inventory);
    }

    @Override
    protected void chargeShipping(List<OrderLine> lines) {
        System.out.println("Charging standard shipping rate");
    }

    @Override
    protected void notifyCustomer(Order order) {
        System.out.println("Notified customer: standard delivery in 5-7 days for " + order.id());
    }
}
