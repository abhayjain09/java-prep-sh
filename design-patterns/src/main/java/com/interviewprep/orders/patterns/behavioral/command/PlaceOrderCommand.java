package com.interviewprep.orders.patterns.behavioral.command;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderLine;

import java.util.List;
import java.util.function.Consumer;

/**
 * CONCRETE COMMAND: placing an order. Captures everything needed to both DO
 * and UNDO the operation — the receiver ({@link Inventory}), the request
 * data (customer, lines), and — critically for undo — enough state to
 * reverse the side effect (releasing exactly the stock that was reserved).
 */
public class PlaceOrderCommand implements OrderCommand {

    private final Inventory inventory;
    private final Customer customer;
    private final List<OrderLine> lines;
    private final Consumer<Order> onPlaced;
    private Order placedOrder; // populated by execute(); needed by undo()

    public PlaceOrderCommand(Inventory inventory, Customer customer, List<OrderLine> lines,
                              Consumer<Order> onPlaced) {
        this.inventory = inventory;
        this.customer = customer;
        this.lines = lines;
        this.onPlaced = onPlaced;
    }

    @Override
    public void execute() {
        for (OrderLine line : lines) {
            inventory.reserve(line.product().sku(), line.quantity());
        }
        placedOrder = new Order("ORD-" + System.nanoTime(), customer);
        lines.forEach(placedOrder::addLine);
        onPlaced.accept(placedOrder);
    }

    @Override
    public void undo() {
        if (placedOrder == null) {
            throw new IllegalStateException("Cannot undo a command that was never executed");
        }
        for (OrderLine line : lines) {
            inventory.release(line.product().sku(), line.quantity());
        }
    }

    @Override
    public String description() {
        return "PlaceOrder[customer=" + customer.name() + ", lines=" + lines.size() + "]";
    }
}
