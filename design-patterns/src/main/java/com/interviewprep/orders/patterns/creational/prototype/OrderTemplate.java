package com.interviewprep.orders.patterns.creational.prototype;

import com.interviewprep.orders.domain.OrderLine;

import java.util.ArrayList;
import java.util.List;

/**
 * A reusable "saved cart" template — e.g. a customer's monthly recurring
 * order, or a merchandiser's starter kit — that gets copied every time a new
 * order is started from it. This is the object the Prototype pattern clones.
 *
 * DELIBERATELY MUTABLE (unlike the domain's records): a template is edited
 * over time (lines added/removed, notes changed) independently of any order
 * copied from it, which is exactly the scenario where "copy this object's
 * current state, then let the copy and the original evolve independently"
 * matters. If OrderTemplate were immutable there would be nothing to clone —
 * you'd just share the reference.
 */
public final class OrderTemplate {

    private String name;
    private final List<OrderLine> lines;
    private String notes;

    public OrderTemplate(String name, List<OrderLine> lines, String notes) {
        this.name = name;
        this.lines = new ArrayList<>(lines);
        this.notes = notes;
    }

    /**
     * COPY CONSTRUCTOR — the "correct" way to implement Prototype in modern
     * Java. See the class Javadoc on {@link NaiveOrderCopy} and the section
     * below for why this is preferred over {@code Object.clone()}.
     *
     * CRITICAL DETAIL: {@code new ArrayList<>(other.lines)} allocates a NEW
     * list and copies the element references into it — this is what makes
     * the copy "deep enough." OrderLine itself is an immutable record (see
     * java-basics), so copying the list's structure is sufficient; we do NOT
     * need to also clone each OrderLine, because nothing can mutate a
     * record's fields after construction. If OrderLine were a mutable class
     * instead, a true deep copy would need to clone each element too, or the
     * original and the copy would share mutable sub-objects — exactly the
     * bug demonstrated in {@link NaiveOrderCopy}.
     */
    public OrderTemplate(OrderTemplate other) {
        this.name = other.name;
        this.lines = new ArrayList<>(other.lines); // new list, same immutable OrderLine elements
        this.notes = other.notes;
    }

    /** Prototype-pattern-flavored entry point: "give me a copy of myself." */
    public OrderTemplate copy() {
        return new OrderTemplate(this);
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<OrderLine> lines() {
        return List.copyOf(lines);
    }

    public void addLine(OrderLine line) {
        lines.add(line);
    }

    public String notes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    @Override
    public String toString() {
        return "OrderTemplate[name=%s, lines=%d, notes=%s]".formatted(name, lines.size(), notes);
    }
}
