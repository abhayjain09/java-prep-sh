package com.interviewprep.orders.patterns.behavioral.memento;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The CARETAKER: stores a history of {@link OrderMemento}s and knows WHEN to
 * save/restore them, but never looks INSIDE one — it treats each memento as
 * an opaque token. This separation (caretaker manages timing/history,
 * originator owns the actual data shape) is what lets {@link OrderEditor}
 * change its internal fields freely without ever breaking the caretaker.
 */
public class OrderHistoryCaretaker {

    private final Deque<OrderMemento> history = new ArrayDeque<>();

    public void save(OrderEditor editor) {
        history.push(editor.saveSnapshot());
    }

    public void undo(OrderEditor editor) {
        if (history.isEmpty()) {
            throw new IllegalStateException("No snapshots to restore");
        }
        editor.restore(history.pop());
    }
}
