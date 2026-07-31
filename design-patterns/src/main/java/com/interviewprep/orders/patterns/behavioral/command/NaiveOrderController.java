package com.interviewprep.orders.patterns.behavioral.command;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderLine;
import com.interviewprep.orders.domain.OrderStatus;

import java.util.List;

/**
 * WRONG — operations (place order, cancel order) are just method calls; the
 * "request" never becomes an object. This makes three common requirements
 * awkward or impossible to add later without a rewrite:
 *
 * 1. NO UNDO: {@link #cancelOrder} releases stock immediately with no record
 *    of what was released or from where — "undo this cancellation" (a very
 *    real customer-service request: "I clicked cancel by mistake") has
 *    nothing to work from. You'd have to re-derive the original order lines
 *    from elsewhere and hope nothing else changed in the meantime.
 * 2. NO QUEUING/DEFERRED EXECUTION: these methods run immediately and only
 *    ever run once, synchronously, at the call site. Queuing "place this
 *    order once inventory is back in stock" or batching a set of operations
 *    to run together requires objects representing the pending operations —
 *    which don't exist here.
 * 3. NO UNIFORM LOGGING/AUDIT: logging "what operations were requested,
 *    with what data, and in what order" means adding logging calls at every
 *    call site by hand — there's no single object type ("a command") that a
 *    generic logger/invoker could iterate over.
 *
 * See {@link OrderCommand} + {@link OrderCommandInvoker}: each operation
 * becomes an object satisfying a common interface, which can be executed,
 * undone, queued, and logged UNIFORMLY, without the invoker knowing what
 * each command actually does.
 */
public class NaiveOrderController {

    private final Inventory inventory;

    public NaiveOrderController(Inventory inventory) {
        this.inventory = inventory;
    }

    public Order placeOrder(Customer customer, List<OrderLine> lines) {
        for (OrderLine line : lines) {
            inventory.reserve(line.product().sku(), line.quantity());
        }
        Order order = new Order("ORD-" + System.nanoTime(), customer);
        lines.forEach(order::addLine);
        return order;
        // If the customer wants to undo this specific placement a moment
        // later, there is nothing here to call — the caller would have to
        // remember the exact lines and manually call inventory.release for
        // each one, hoping it still matches what was actually reserved.
    }

    public void cancelOrder(Order order) {
        order.getLines().forEach(line -> inventory.release(line.product().sku(), line.quantity()));
        order.transitionTo(OrderStatus.CANCELLED);
        // Same problem in reverse: no record of this action exists after
        // the fact for undo, logging, or replay purposes.
    }
}
