package com.interviewprep.orders.patterns.behavioral.memento;

import com.interviewprep.orders.domain.OrderLine;

import java.util.List;

/**
 * The MEMENTO: an immutable snapshot of an {@link OrderEditor}'s editable
 * state, captured at a point in time. Deliberately has NO public mutator
 * methods and NO public constructor usable outside its originator's
 * package-visibility contract with {@link OrderEditor} — callers
 * ({@link OrderHistoryCaretaker}) can hold onto mementos and pass them BACK
 * to the originator to restore, but cannot peek inside or modify one, which
 * is what keeps the originator's internals encapsulated (the defining
 * constraint of Memento: the caretaker stores state it cannot read).
 */
public final class OrderMemento {

    private final List<OrderLine> lines;
    private final String notes;

    // Package-private: only OrderEditor (in this same package) can create
    // or unpack one of these — see OrderEditor.saveSnapshot()/restore().
    OrderMemento(List<OrderLine> lines, String notes) {
        this.lines = List.copyOf(lines); // defensive copy — same reasoning as Order.getLines()
        this.notes = notes;
    }

    List<OrderLine> lines() {
        return lines;
    }

    String notes() {
        return notes;
    }
}
