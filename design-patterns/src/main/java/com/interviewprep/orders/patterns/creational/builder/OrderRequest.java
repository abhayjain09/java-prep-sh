package com.interviewprep.orders.patterns.creational.builder;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.OrderLine;

import java.util.List;

/**
 * The complex, mostly-optional-field object this Builder example constructs:
 * a checkout request capturing everything a customer might attach to an
 * order beyond the required line items — gift wrapping, a note to the
 * recipient, an optional discount code, express shipping.
 *
 * Immutable once built (all fields final, list defensively copied) — the
 * same defensive-copy reasoning as {@code Order.getLines()} in java-basics.
 */
public final class OrderRequest {

    private final Customer customer;
    private final List<OrderLine> lines;
    private final String note;              // nullable — optional
    private final String discountCode;      // nullable — optional
    private final boolean giftWrap;         // defaults false
    private final boolean expressShipping;  // defaults false

    // Package-private: only OrderRequestBuilder constructs this class, so
    // every OrderRequest that exists has already passed the builder's
    // validation in build(). No other code path can create a half-valid one.
    OrderRequest(Customer customer, List<OrderLine> lines, String note,
                 String discountCode, boolean giftWrap, boolean expressShipping) {
        this.customer = customer;
        this.lines = List.copyOf(lines); // defensive copy, same rationale as Order.getLines()
        this.note = note;
        this.discountCode = discountCode;
        this.giftWrap = giftWrap;
        this.expressShipping = expressShipping;
    }

    public Customer customer() {
        return customer;
    }

    public List<OrderLine> lines() {
        return lines;
    }

    public String note() {
        return note;
    }

    public String discountCode() {
        return discountCode;
    }

    public boolean giftWrap() {
        return giftWrap;
    }

    public boolean expressShipping() {
        return expressShipping;
    }

    @Override
    public String toString() {
        return "OrderRequest[customer=%s, lines=%d, note=%s, discountCode=%s, giftWrap=%b, express=%b]"
                .formatted(customer.name(), lines.size(), note, discountCode, giftWrap, expressShipping);
    }
}
