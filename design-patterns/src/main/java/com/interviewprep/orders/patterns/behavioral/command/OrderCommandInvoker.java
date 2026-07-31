package com.interviewprep.orders.patterns.behavioral.command;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * CORRECT — the INVOKER: executes any {@link OrderCommand} and keeps a
 * history stack, enabling generic undo of the MOST RECENT command without
 * knowing what that command actually does. This is the payoff of Command:
 * new command types (refunds, restocks, address changes) automatically get
 * undo/history/logging support just by implementing the interface — the
 * invoker never needs to change.
 *
 * USAGE EXAMPLE:
 * <pre>{@code
 * OrderCommandInvoker invoker = new OrderCommandInvoker();
 * invoker.run(new PlaceOrderCommand(inventory, customer, lines, order -> System.out.println("placed " + order)));
 * // ... customer service rep clicks "undo" ...
 * invoker.undoLast();
 * }</pre>
 */
public class OrderCommandInvoker {

    private final Deque<OrderCommand> history = new ArrayDeque<>();

    public void run(OrderCommand command) {
        command.execute();
        history.push(command);
        System.out.println("Executed: " + command.description());
    }

    public void undoLast() {
        if (history.isEmpty()) {
            throw new IllegalStateException("No commands to undo");
        }
        OrderCommand last = history.pop();
        last.undo();
        System.out.println("Undone: " + last.description());
    }
}
