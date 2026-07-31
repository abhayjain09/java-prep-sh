package com.interviewprep.orders.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parameterized tests for {@link OrderStatus#canTransitionTo(OrderStatus)}, covering
 * every one of the 5 x 5 = 25 (from, to) pairs over the five OrderStatus constants.
 *
 * WHY A PARAMETERIZED TEST HERE INSTEAD OF 25 HAND-WRITTEN @Test METHODS: the logic
 * under test (OrderStatus.legalNextStates()) is a pure lookup table with no branching
 * beyond "which status is this" -- exactly the shape @ParameterizedTest exists for.
 * Twenty-five near-identical @Test methods (testPendingToConfirmedIsLegal(),
 * testPendingToShippedIsIllegal(), ...) would be enormous copy-paste boilerplate that
 * obscures the one line of real logic (canTransitionTo returns X) behind naming
 * effort. @CsvSource lets the *data* (which pairs, which expected outcome) do the
 * talking, with one @Test method's worth of actual assertion code.
 *
 * WHY EVERY PAIR, INCLUDING THE ILLEGAL ONES, RATHER THAN JUST THE 4 LEGAL
 * TRANSITIONS: OrderStatus's whole value (per java-basics/README.md) is that illegal
 * transitions are rejected -- DELIVERED -> PENDING must never be legal. A test suite
 * that only asserted the happy-path transitions would pass even if
 * legalNextStates() were buggy in a way that also made illegal transitions legal
 * (e.g. accidentally returning Set.of(values()) for every constant). Testing the full
 * grid is what actually pins down "these five transitions are legal and the other
 * twenty are not," not just "these five work."
 */
class OrderStatusTest {

    @ParameterizedTest(name = "{0} -> {1} should be legal = {2}")
    @CsvSource({
            // from,       to,         expectedLegal
            // This table mirrors OrderStatus.legalNextStates() by hand, on purpose --
            // it is NOT derived from the production code under test. If someone edits
            // legalNextStates() to (accidentally or not) change the business rules,
            // this independently-written table is what catches the drift.
            "PENDING,   PENDING,   false",
            "PENDING,   CONFIRMED, true",
            "PENDING,   SHIPPED,   false",
            "PENDING,   DELIVERED, false",
            "PENDING,   CANCELLED, true",

            "CONFIRMED, PENDING,   false",
            "CONFIRMED, CONFIRMED, false",
            "CONFIRMED, SHIPPED,   true",
            "CONFIRMED, DELIVERED, false",
            "CONFIRMED, CANCELLED, true",

            "SHIPPED,   PENDING,   false",
            "SHIPPED,   CONFIRMED, false",
            "SHIPPED,   SHIPPED,   false",
            "SHIPPED,   DELIVERED, true",
            "SHIPPED,   CANCELLED, false",

            "DELIVERED, PENDING,   false",
            "DELIVERED, CONFIRMED, false",
            "DELIVERED, SHIPPED,   false",
            "DELIVERED, DELIVERED, false",
            "DELIVERED, CANCELLED, false",

            "CANCELLED, PENDING,   false",
            "CANCELLED, CONFIRMED, false",
            "CANCELLED, SHIPPED,   false",
            "CANCELLED, DELIVERED, false",
            "CANCELLED, CANCELLED, false",
    })
    void canTransitionToMatchesTheExpectedLegalityForEveryPair(
            OrderStatus from, OrderStatus to, boolean expectedLegal) {
        // JUnit 5 implicitly converts each CSV column to the parameter's declared
        // type -- "PENDING" (a String in the CSV source) becomes OrderStatus.PENDING
        // and "true"/"false" become a boolean, with no explicit conversion code needed
        // on our side (built-in implicit converters for enums and primitives).
        assertThat(from.canTransitionTo(to)).isEqualTo(expectedLegal);
    }
}
