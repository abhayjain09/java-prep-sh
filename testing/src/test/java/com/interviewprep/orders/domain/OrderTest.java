package com.interviewprep.orders.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link Order}. Like InventoryTest, no mocks -- Order's only
 * collaborators (Customer, Product, OrderLine, OrderStatus) are plain, cheap,
 * immutable value types, so using the real ones is simpler and more honest than
 * mocking them would be. Mocking is a tool for isolating from *expensive or
 * side-effecting* collaborators (see OrderServiceTest for where that actually
 * matters), not a default to reach for on every dependency.
 */
class OrderTest {

    private Customer customer;
    private Product widget;
    private Product gadget;

    @BeforeEach
    void setUp() {
        customer = new Customer("CUST-1", "Ada Lovelace", "ada@example.com");
        widget = new Product("SKU-WIDGET", "Widget", new BigDecimal("9.99"));
        gadget = new Product("SKU-GADGET", "Gadget", new BigDecimal("19.99"));
    }

    @Test
    void addLineAndTotalAmountSumEveryLine() {
        Order order = new Order("ORD-1", customer);

        order.addLine(new OrderLine(widget, 2)); // 2 * 9.99 = 19.98
        order.addLine(new OrderLine(gadget, 1)); // 1 * 19.99 = 19.99

        // isEqualByComparingTo, NOT isEqualTo: BigDecimal.equals() also compares scale
        // (9.990 and 9.99 are "not equal" under equals() despite being the same value),
        // which is exactly the BigDecimal trap called out in java-basics/README.md's
        // Collections/Generics section on boxed-type comparison pitfalls. AssertJ's
        // isEqualByComparingTo delegates to compareTo(), which is scale-independent and
        // is what you almost always mean when comparing monetary BigDecimals in a test.
        assertThat(order.totalAmount()).isEqualByComparingTo("39.97");
    }

    @Test
    void totalAmountOfAnOrderWithNoLinesIsZero() {
        Order order = new Order("ORD-1", customer);

        assertThat(order.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getLinesReturnsAnImmutableDefensiveCopy() {
        Order order = new Order("ORD-1", customer);
        order.addLine(new OrderLine(widget, 1));

        List<OrderLine> lines = order.getLines();

        // Order.getLines() is documented (java-basics) to return List.copyOf(lines) --
        // an immutable snapshot -- specifically so callers cannot reach into and mutate
        // an Order's real internal state through the getter. This assertion is the test
        // for that contract: attempting to mutate the returned list must fail loudly
        // (UnsupportedOperationException) rather than silently succeeding.
        assertThatThrownBy(() -> lines.add(new OrderLine(gadget, 1)))
                .isInstanceOf(UnsupportedOperationException.class);

        // And the mutation attempt (even though it threw before actually inserting
        // anything) must not have had any side effect on the order's real state --
        // the order should still report exactly the one line added before the getter
        // was called.
        assertThat(order.getLines()).hasSize(1);
    }

    @Test
    void transitionToALegalNextStatusSucceeds() {
        Order order = new Order("ORD-1", customer);

        order.transitionTo(OrderStatus.CONFIRMED); // PENDING -> CONFIRMED is legal

        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void transitionToAnIllegalStatusThrowsAndLeavesStatusUnchanged() {
        Order order = new Order("ORD-1", customer);

        // PENDING -> DELIVERED skips CONFIRMED and SHIPPED entirely -- illegal per
        // OrderStatus.legalNextStates(). Order.transitionTo() delegates the legality
        // check to OrderStatus and is documented to throw IllegalStateException
        // *before* mutating status -- both halves of that contract are asserted below.
        assertThatThrownBy(() -> order.transitionTo(OrderStatus.DELIVERED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ORD-1")
                .hasMessageContaining("PENDING")
                .hasMessageContaining("DELIVERED");

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
    }
}
