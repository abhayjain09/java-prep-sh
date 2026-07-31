package com.interviewprep.orders.patterns.creational.factorymethod;

/**
 * CORRECT — the classic GoF Factory Method shape: an abstract "Creator" class
 * defines the algorithm that USES a product (here, {@link #describe()}), and
 * declares a factory method ({@link #createProcessor()}) that subclasses
 * override to decide WHICH concrete product gets created. The Creator's own
 * logic never mentions a concrete PaymentProcessor subclass by name.
 *
 * DISTINCTION FROM "SIMPLE FACTORY" (a very common interview mix-up): a
 * simple factory (see {@link PaymentProcessorFactory} below) is a single
 * class with a big switch/map that returns different concrete types — it
 * centralizes creation but is not itself polymorphic. True Factory Method
 * (this class) pushes the decision into subclassing/polymorphism: each
 * concrete Creator IS a piece of the decision, chosen at the call site by
 * which Creator subclass is instantiated, and new payment types are added by
 * adding a new Creator subclass (Open/Closed — no existing class is edited).
 *
 * WHEN TO PREFER SIMPLE FACTORY INSTEAD: when there's no real "algorithm
 * around the product" to template — just "give me a PaymentProcessor for
 * this enum value." That's most CRUD-shaped systems, which is exactly why
 * {@link PaymentProcessorFactory}'s enum-keyed map is what you'll actually
 * reach for day-to-day; this class exists so you can recognize and explain
 * true Factory Method when an interviewer draws the GoF diagram and asks
 * "what's the difference?"
 */
public abstract class PaymentProcessorCreator {

    /** The factory method — each subclass decides the concrete product. */
    protected abstract PaymentProcessor createProcessor();

    /**
     * Template-ish method that uses the product without knowing its concrete
     * type — this is the part a simple factory doesn't give you "for free,"
     * because a simple factory has no surrounding class hierarchy to hang
     * shared behavior off of.
     */
    public final String describe() {
        PaymentProcessor processor = createProcessor();
        return "Processor ready: " + processor.name();
    }
}
