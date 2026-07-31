package com.interviewprep.orders.patterns.behavioral.chainofresponsibility;

import com.interviewprep.orders.domain.OrderLine;

import java.math.BigDecimal;

/**
 * WRONG — one monolithic method performs stock check, fraud check, AND
 * credit check, nested/sequenced by hand inside a single block.
 *
 * WHY THIS IS A PROBLEM:
 * 1. CAN'T REORDER, SKIP, OR REUSE INDIVIDUAL CHECKS: if an admin "override"
 *    flow needs every check EXCEPT the credit check (a common real
 *    requirement — e.g. VIP customers bypass credit limits but still need
 *    stock/fraud checks), there's no way to compose that from this method
 *    without a NEW method that duplicates two-thirds of this one.
 * 2. GROWS INTO AN UNREADABLE WALL: a fourth check (say, "shipping address
 *    validation") means editing this method's body again, growing an
 *    already-long method rather than adding one small, independently
 *    testable unit.
 * 3. TESTING ONE CHECK IN ISOLATION IS AWKWARD: to unit-test "does the
 *    credit check correctly reject an over-limit order," you must first
 *    satisfy the stock check and fraud check with valid dummy data, even
 *    though this test has nothing to do with either.
 *
 * See {@link OrderValidationHandler} + {@link StockCheckHandler} /
 * {@link FraudCheckHandler} / {@link CreditCheckHandler}: each check is an
 * independent, unit-testable class; the ORDER and COMPOSITION of checks is
 * assembled once at wiring time (e.g. {@code stockCheck.setNext(fraudCheck)
 * .setNext(creditCheck)}), and different chains (with different checks, in
 * different orders) can coexist for different flows (e.g. an
 * admin-override chain that skips CreditCheckHandler entirely).
 */
public class NaiveOrderValidator {

    public void validate(OrderValidationRequest request) {
        // Check 1: stock.
        for (OrderLine line : request.lines()) {
            int available = request.inventory().stockOf(line.product().sku());
            if (available < line.quantity()) {
                throw new OrderRejectedException("Insufficient stock for " + line.product().sku());
            }
        }

        // Check 2: fraud — nested inside the same method, no way to run
        // this check alone without also running check 1 first.
        if (request.flaggedForFraudReview()) {
            throw new OrderRejectedException("Customer flagged for fraud review");
        }

        // Check 3: credit — same problem, plus this method just keeps growing.
        BigDecimal orderTotal = request.lines().stream()
                .map(OrderLine::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (request.customerCurrentBalance().add(orderTotal).compareTo(request.customerCreditLimit()) > 0) {
            throw new OrderRejectedException("Order would exceed credit limit");
        }
    }
}
