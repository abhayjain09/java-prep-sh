package com.interviewprep.orders.patterns.creational.singleton;

import com.interviewprep.orders.domain.Inventory;

/**
 * WRONG — a textbook "lazy initialization" Singleton with THREE separate bugs
 * layered on top of the pattern's deeper design problem.
 *
 * INTENT (what the developer wanted): exactly one {@link Inventory} shared by
 * every part of the application, reachable from anywhere without threading a
 * reference through every constructor — "global state, but for a good reason."
 *
 * BUG 1 — NOT THREAD-SAFE: two threads can both observe {@code instance == null},
 * both proceed into the branch, and both construct a new Inventory. Whichever
 * assignment happens last "wins" and the other Inventory (and any stock already
 * reserved against it) is silently discarded. This is the exact same
 * check-then-act race shape as {@code Inventory.reserve()}'s documented bug in
 * Module 1 — Singleton lazy-init is one of the most common places this race
 * shows up in real codebases.
 *
 * BUG 2 — HIDDEN GLOBAL MUTABLE STATE: any class, anywhere in the codebase, can
 * call {@code NaiveInventoryRegistry.getInstance()} and mutate shared state.
 * There is no way to see, from a class's constructor or method signatures,
 * that it depends on the inventory — the dependency is invisible until you
 * read the method body. Compare this to {@code OrderService}'s constructor
 * (Module 1), which takes an {@code Inventory} explicitly: reading the
 * signature alone tells you the dependency exists.
 *
 * BUG 3 — UNTESTABLE: because the instance is a static field created via
 * {@code new Inventory()} the first time it's touched, tests cannot substitute
 * a fake/mock Inventory, and state LEAKS BETWEEN TESTS — test A restocks SKU-1,
 * test B (run after A in the same JVM) sees that stock still there, because
 * the static field is never reset. This is the single biggest reason senior
 * interviewers push back on Singletons: it isn't the pattern's mechanics, it's
 * what the mechanics do to testability.
 *
 * See {@link InventoryRegistry} for a version that fixes bug 1 and 2's worst
 * symptom (thread-safety, explicit access point) but the class-level Javadoc
 * there explains why even the "correct" classic Singleton is still usually
 * the wrong call in a Spring/DI application (Module 5) — the deepest fix is
 * architectural, not syntactic.
 */
public final class NaiveInventoryRegistry {

    // Not "volatile" and not synchronized — a second thread may see a
    // partially-constructed Inventory reference due to instruction reordering
    // (the Java Memory Model does not guarantee this write is visible to
    // other threads without a happens-before edge), on top of the racy
    // null-check below.
    private static NaiveInventoryRegistry instance;

    private final Inventory inventory = new Inventory();

    // Private constructor only *looks* like it prevents multiple instances.
    // It doesn't stop the race below from creating two.
    private NaiveInventoryRegistry() {
    }

    public static NaiveInventoryRegistry getInstance() {
        if (instance == null) {                 // <-- CHECK
            instance = new NaiveInventoryRegistry(); // <-- ACT (racy: two threads
        }                                        //     can both pass the check
        return instance;
    }

    public Inventory inventory() {
        return inventory;
    }
}
