package com.interviewprep.orders.patterns.behavioral.command;

/**
 * The COMMAND interface: encapsulates a request (an operation plus its
 * arguments, already bound) as an object, so it can be queued, logged,
 * retried, or undone uniformly — regardless of what the operation actually
 * does underneath.
 */
public interface OrderCommand {
    void execute();
    void undo();

    /** Human-readable description, useful for logging/audit trails. */
    String description();
}
