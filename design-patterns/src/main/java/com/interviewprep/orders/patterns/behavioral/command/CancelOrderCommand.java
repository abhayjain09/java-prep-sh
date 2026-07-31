package com.interviewprep.orders.patterns.behavioral.command;

import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderStatus;

/**
 * CONCRETE COMMAND: cancelling an order releases its reserved stock. The
 * "undo" of a cancellation is re-reserving that same stock and reverting the
 * status — modeling a realistic "customer service rep cancelled by mistake,
 * undo it" scenario that a command history (see {@link OrderCommandInvoker})
 * makes trivial to support.
 */
public class CancelOrderCommand implements OrderCommand {

    private final Inventory inventory;
    private final Order order;
    private OrderStatus statusBeforeCancel;

    public CancelOrderCommand(Inventory inventory, Order order) {
        this.inventory = inventory;
        this.order = order;
    }

    @Override
    public void execute() {
        statusBeforeCancel = order.status();
        order.getLines().forEach(line -> inventory.release(line.product().sku(), line.quantity()));
        order.transitionTo(OrderStatus.CANCELLED);
    }

    @Override
    public void undo() {
        if (statusBeforeCancel == null) {
            throw new IllegalStateException("Cannot undo a command that was never executed");
        }
        // Re-reserve the stock we released, and manually restore status —
        // Order.transitionTo() cannot go CANCELLED -> anything (it's a
        // terminal state, see OrderStatus.legalNextStates() in java-basics),
        // so a real system would model this as a fresh compensating
        // operation rather than a literal reverse-transition. Shown here at
        // the Inventory level only, to keep this example focused on Command
        // mechanics rather than re-litigating the state machine.
        order.getLines().forEach(line -> inventory.reserve(line.product().sku(), line.quantity()));
    }

    @Override
    public String description() {
        return "CancelOrder[orderId=" + order.id() + "]";
    }
}
