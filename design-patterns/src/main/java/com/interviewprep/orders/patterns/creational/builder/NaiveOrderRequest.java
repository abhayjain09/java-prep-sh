package com.interviewprep.orders.patterns.creational.builder;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.OrderLine;

import java.util.List;

/**
 * WRONG — "telescoping constructors": one overload per meaningful
 * combination of optional parameters. This class only has FOUR optional
 * fields (note, discountCode, giftWrap, expressShipping) and already needs
 * multiple overloads to cover common combinations; a fully general set would
 * need 2^4 = 16 overloads to cover every combination directly (this class
 * only bothers with a few, which is itself a symptom — the rest silently
 * become "not supported without also setting other things you don't want").
 *
 * PROBLEMS THIS CAUSES:
 * 1. CALL-SITE AMBIGUITY: {@code new NaiveOrderRequest(customer, lines, true, false)}
 *    — is that (giftWrap, expressShipping) or (expressShipping, giftWrap)? Both
 *    are booleans, so the compiler can't help, and a transposed pair of
 *    arguments compiles cleanly and fails silently at runtime (wraps the
 *    wrong thing, ships the wrong way).
 * 2. IT DOESN'T SCALE: adding a fifth optional field (say, "leaveAtDoor")
 *    means deciding which of the existing overloads also need a version with
 *    the new parameter — the combinatorial explosion is exactly why most
 *    codebases give up and fall back to one giant constructor with every
 *    parameter, most of them null/false at most call sites, which is its own
 *    readability problem (a 7-argument constructor call is unreadable without
 *    an IDE showing parameter name hints).
 * 3. NO PARTIAL CONSTRUCTION / VALIDATION STAGING: nothing stops passing
 *    invalid combinations (e.g. a blank discount code) until this object is
 *    already "fully built" — there's no natural place to validate as fields
 *    are assembled versus at the end.
 *
 * See {@link OrderRequestBuilder} for the fix: one fluent chain, optional
 * fields set only when they apply, validated once in {@code build()}.
 */
public final class NaiveOrderRequest {

    private final Customer customer;
    private final List<OrderLine> lines;
    private final String note;
    private final String discountCode;
    private final boolean giftWrap;
    private final boolean expressShipping;

    // Overload 1: bare minimum.
    public NaiveOrderRequest(Customer customer, List<OrderLine> lines) {
        this(customer, lines, null, null, false, false);
    }

    // Overload 2: with a note.
    public NaiveOrderRequest(Customer customer, List<OrderLine> lines, String note) {
        this(customer, lines, note, null, false, false);
    }

    // Overload 3: with gift wrap and express shipping — but no way to get
    // ONLY express shipping without gift wrap through this overload; a
    // caller wanting that must fall back to the full constructor and pass
    // null/false for everything else, which they'll copy-paste incorrectly
    // sooner or later.
    public NaiveOrderRequest(Customer customer, List<OrderLine> lines,
                              boolean giftWrap, boolean expressShipping) {
        this(customer, lines, null, null, giftWrap, expressShipping);
    }

    // The "full" constructor every overload eventually delegates to — six
    // positional parameters, two of them adjacent booleans (bug source #1
    // above).
    public NaiveOrderRequest(Customer customer, List<OrderLine> lines, String note,
                              String discountCode, boolean giftWrap, boolean expressShipping) {
        this.customer = customer;
        this.lines = lines;
        this.note = note;
        this.discountCode = discountCode;
        this.giftWrap = giftWrap;
        this.expressShipping = expressShipping;
    }

    @Override
    public String toString() {
        return "NaiveOrderRequest[customer=%s, lines=%d, note=%s, discountCode=%s, giftWrap=%b, express=%b]"
                .formatted(customer.name(), lines.size(), note, discountCode, giftWrap, expressShipping);
    }
}
