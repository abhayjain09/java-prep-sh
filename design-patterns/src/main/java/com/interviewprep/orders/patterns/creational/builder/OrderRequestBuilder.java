package com.interviewprep.orders.patterns.creational.builder;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.OrderLine;

import java.util.ArrayList;
import java.util.List;

/**
 * CORRECT — Builder pattern: construct {@link OrderRequest} through a fluent
 * chain of named, self-documenting methods instead of positional
 * constructor arguments.
 *
 * FIXES FROM {@link NaiveOrderRequest}:
 * - Call-site clarity: {@code .withGiftWrap()} and {@code .withExpressShipping()}
 *   can never be transposed — each is its own named method, not a positional
 *   boolean.
 * - Scales to new optional fields: adding "leaveAtDoor" means adding one
 *   {@code withLeaveAtDoor()} method; no existing call site or overload needs
 *   to change, and no combinatorial explosion of constructors follows.
 * - Validation happens once, in {@link #build()}, after every field is set —
 *   a natural single place to enforce cross-field rules (e.g. "can't be both
 *   gift-wrapped AND marked as a business/tax-exempt order" — not
 *   implemented here, but this is where such a rule would go).
 *
 * USAGE EXAMPLE:
 * <pre>{@code
 * OrderRequest request = new OrderRequestBuilder(customer)
 *         .addLine(new OrderLine(product, 2))
 *         .withNote("Happy birthday!")
 *         .withGiftWrap()
 *         .withExpressShipping()
 *         .build();
 * }</pre>
 * Reads like a sentence, and every optional feature is opt-in by name.
 */
public final class OrderRequestBuilder {

    private final Customer customer;
    private final List<OrderLine> lines = new ArrayList<>();
    private String note;
    private String discountCode;
    private boolean giftWrap;
    private boolean expressShipping;

    public OrderRequestBuilder(Customer customer) {
        if (customer == null) {
            throw new IllegalArgumentException("OrderRequest requires a customer");
        }
        this.customer = customer;
    }

    public OrderRequestBuilder addLine(OrderLine line) {
        this.lines.add(line);
        return this; // returning `this` is what makes the chain fluent
    }

    public OrderRequestBuilder withNote(String note) {
        this.note = note;
        return this;
    }

    public OrderRequestBuilder withDiscountCode(String discountCode) {
        this.discountCode = discountCode;
        return this;
    }

    public OrderRequestBuilder withGiftWrap() {
        this.giftWrap = true;
        return this;
    }

    public OrderRequestBuilder withExpressShipping() {
        this.expressShipping = true;
        return this;
    }

    /**
     * Validates the assembled state and produces an immutable OrderRequest.
     * This is the ONE place cross-field validation belongs — e.g. "at least
     * one line item is required" — rather than scattered across constructor
     * overloads as in the naive version.
     */
    public OrderRequest build() {
        if (lines.isEmpty()) {
            throw new IllegalStateException("OrderRequest requires at least one line");
        }
        return new OrderRequest(customer, lines, note, discountCode, giftWrap, expressShipping);
    }
}
