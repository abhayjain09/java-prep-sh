package com.interviewprep.orders.patterns.behavioral.memento;

import com.interviewprep.orders.domain.OrderLine;

import java.util.ArrayList;
import java.util.List;

/**
 * The ORIGINATOR: the object whose state gets snapshotted and restored.
 * Represents an in-progress, still-editable order (before checkout) that a
 * customer or CS rep might want to roll back after a series of edits (e.g.
 * "undo my last three changes to this cart").
 *
 * ONLY this class knows how to build ({@link #saveSnapshot()}) or unpack
 * ({@link #restore(OrderMemento)}) a memento — {@link OrderHistoryCaretaker}
 * just stores the opaque objects this class hands it.
 */
public class OrderEditor {

    private final List<OrderLine> lines = new ArrayList<>();
    private String notes = "";

    public void addLine(OrderLine line) {
        lines.add(line);
    }

    public void removeLine(OrderLine line) {
        lines.remove(line);
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<OrderLine> lines() {
        return List.copyOf(lines);
    }

    public String notes() {
        return notes;
    }

    /** Captures current state into an opaque, immutable memento. */
    public OrderMemento saveSnapshot() {
        return new OrderMemento(lines, notes);
    }

    /** Restores state from a previously captured memento. */
    public void restore(OrderMemento memento) {
        lines.clear();
        lines.addAll(memento.lines());
        notes = memento.notes();
    }
}
