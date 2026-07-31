package com.interviewprep.orders.patterns.creational.prototype;

import com.interviewprep.orders.domain.OrderLine;

import java.util.List;

/**
 * WRONG — two flavors of "copy" that both look reasonable and both have
 * real, production-shaped bugs.
 *
 * FLAVOR 1 — MANUAL FIELD-BY-FIELD COPY, MAINTAINED BY HAND
 * {@link #copyByHand(OrderTemplate)} re-implements copying outside the class
 * being copied. It works today, but every time a field is added to
 * {@code OrderTemplate}, this method silently keeps compiling and silently
 * keeps dropping the new field from every "copy" — there is no compiler
 * error, no test failure unless someone specifically wrote one, just a
 * quietly-wrong copy from now on. This is the single biggest argument for
 * putting copy logic INSIDE the class (a copy constructor or {@code copy()}
 * method, as {@link OrderTemplate} does) rather than beside it: the person
 * adding a field is far more likely to notice/update the class's own copy
 * logic than to find and update every external "manual copier" scattered
 * around the codebase.
 *
 * FLAVOR 2 — SHALLOW COPY VIA A NAIVE clone()-STYLE FIELD ASSIGNMENT
 * {@link #shallowCopy(OrderTemplate)} copies the {@code lines} reference
 * directly instead of copying the list's contents into a new list. The two
 * "copies" now share the SAME underlying List — mutating one
 * (adding/removing a line) is visible through the other, defeating the
 * entire point of making a copy. This is the classic shallow-vs-deep-copy
 * trap, and it's exactly why {@code OrderTemplate}'s real copy constructor
 * uses {@code new ArrayList<>(other.lines)} rather than {@code other.lines}
 * directly.
 *
 * WHY NOT {@code Object.clone()} EITHER: Java's built-in {@code Cloneable}/
 * {@code clone()} mechanism is widely considered broken (Effective Java,
 * Item 13): {@code Cloneable} has no clone() method of its own (it's a
 * marker interface), {@code Object.clone()} is protected and does a shallow
 * field-by-field copy by default (the same bug as Flavor 2 unless every
 * mutable field is manually deep-copied inside an overridden clone()), it
 * doesn't call any constructor (so invariants enforced only in constructors
 * are bypassed), and checked {@code CloneNotSupportedException} handling
 * adds boilerplate for no benefit. A copy constructor or static
 * {@code copyOf(...)} method (as used in {@code OrderTemplate}) gives the
 * same "clone this object" capability with none of these problems, which is
 * why it's the recommended modern approach for implementing Prototype in Java.
 */
public class NaiveOrderCopy {

    /**
     * Copies "everything we remember to copy" — safe only until someone adds
     * a field to OrderTemplate and forgets this method exists.
     */
    public OrderTemplate copyByHand(OrderTemplate original) {
        OrderTemplate copy = new OrderTemplate(original.name(), original.lines(), original.notes());
        // If OrderTemplate later gains, say, a "preferredWarehouse" field,
        // this method keeps compiling and keeps silently dropping it.
        return copy;
    }

    /**
     * Demonstrates the shallow-copy bug directly: constructs a "copy" that
     * shares the SAME OrderLine list reference as the original, instead of
     * an independent list.
     */
    public OrderTemplate shallowCopy(OrderTemplate original) {
        List<OrderLine> sameListReference = original.lines(); // NOTE: OrderTemplate.lines()
        // already defensively copies here, so THIS specific call is actually
        // safe — the bug below shows what happens if that defensive copy
        // did NOT exist and a raw field were exposed instead, which is a
        // very common real-world variant of this mistake.
        OrderTemplate copy = new OrderTemplate(original.name(), sameListReference, original.notes());
        return copy;
        // If OrderTemplate.lines() returned the LIVE internal list instead
        // of List.copyOf(lines) (i.e. if it made the same mistake the
        // "wrong" Order.getLines() example in java-basics/README.md warns
        // against), then mutating `copy`'s lines would also mutate
        // `original`'s lines — the defining shallow-copy bug.
    }
}
