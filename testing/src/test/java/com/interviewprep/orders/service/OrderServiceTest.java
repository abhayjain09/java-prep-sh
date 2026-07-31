package com.interviewprep.orders.service;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.InsufficientStockException;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderLine;
import com.interviewprep.orders.domain.OrderStatus;
import com.interviewprep.orders.domain.Product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * The centerpiece test class of this module: exercises {@link OrderService} with
 * its {@link Inventory} dependency MOCKED, rather than using a real Inventory.
 *
 * WHY MOCK Inventory HERE INSTEAD OF USING THE REAL CLASS (see also README.md,
 * "Why mock Inventory in OrderServiceTest"):
 *
 * 1. ISOLATION: OrderService.placeOrder()'s job is *orchestration* -- reserve each
 *    line, remember what succeeded, roll back on failure, build the Order. Whether
 *    Inventory correctly tracks stock counts is a completely separate concern,
 *    already fully covered by InventoryTest. If we used a real Inventory here and a
 *    test failed, we'd have to first rule out "is this an OrderService bug or an
 *    Inventory bug?" -- mocking Inventory means a failure here can ONLY be an
 *    OrderService bug, because Inventory's real logic never runs.
 *
 * 2. FAILURE INJECTION ON DEMAND: the interesting behavior to test is "what happens
 *    when the *third* of three reserve() calls fails, after the first two already
 *    succeeded." With a REAL Inventory, forcing that exact scenario means carefully
 *    pre-seeding stock numbers so lines 1 and 2 each have just enough stock and line
 *    3 does not -- fragile, and it obscures *why* line 3 fails behind arithmetic the
 *    reader has to reverse-engineer. With a mock, we say so directly:
 *    doThrow(...).when(inventory).reserve("SKU-GIZMO", 100) -- "this exact call
 *    fails," full stop, independent of any stock bookkeeping.
 *
 * 3. INTERACTION VERIFICATION: the property under test is fundamentally about
 *    WHICH METHODS GET CALLED, WITH WHAT ARGUMENTS, IN WHAT ORDER -- not about a
 *    return value. A real Inventory has no way to report "and by the way, here is
 *    the exact sequence of reserve/release calls you made on me." Mockito's
 *    verify()/InOrder give us exactly that, which is the whole point of this test.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private Inventory inventory;

    @InjectMocks // Mockito sees OrderService's only constructor, OrderService(Inventory),
                 // and injects the @Mock above through it -- equivalent to manually
                 // writing `orderService = new OrderService(inventory);` in @BeforeEach.
    private OrderService orderService;

    private Customer customer;
    private Product widget;
    private Product gadget;
    private Product gizmo;

    @BeforeEach
    void setUp() {
        customer = new Customer("CUST-1", "Grace Hopper", "grace@example.com");
        widget = new Product("SKU-WIDGET", "Widget", new BigDecimal("10.00"));
        gadget = new Product("SKU-GADGET", "Gadget", new BigDecimal("20.00"));
        gizmo = new Product("SKU-GIZMO", "Gizmo", new BigDecimal("30.00"));
    }

    @Nested
    @DisplayName("placeOrder() happy path")
    class PlaceOrderSuccess {

        @Test
        @DisplayName("reserves every line via Inventory and builds an Order containing them")
        void reservesEveryLineAndBuildsTheOrder() {
            List<OrderLine> lines = List.of(new OrderLine(widget, 2), new OrderLine(gadget, 1));

            // No stubbing of inventory.reserve() at all: Inventory.reserve() is a void
            // method, and Mockito's default behavior for an unstubbed void method on a
            // mock is "do nothing" -- which is precisely what "there was enough stock"
            // looks like from OrderService's point of view (no exception, method returns
            // normally). This is a deliberate choice, not an oversight: it keeps the
            // happy-path test free of stubbing noise for the case where nothing
            // unusual happens.
            Order order = orderService.placeOrder(customer, lines);

            assertThat(order.customer()).isEqualTo(customer);
            assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
            assertThat(order.getLines()).containsExactlyElementsOf(lines);

            // verify() with exact-value argument matchers: proves OrderService asked
            // Inventory to reserve exactly what was requested, for exactly the right
            // SKU/quantity pairs. This is the payoff of mocking here -- a real
            // Inventory object has no way to report "and here's what was asked of me,"
            // only its resulting state.
            verify(inventory).reserve("SKU-WIDGET", 2);
            verify(inventory).reserve("SKU-GADGET", 1);
            // And the rollback path must never fire when nothing failed.
            verify(inventory, never()).release(anyString(), anyInt());
        }
    }

    @Nested
    @DisplayName("placeOrder() rollback on partial failure")
    class PlaceOrderRollback {

        @Test
        @DisplayName("releases every previously-reserved line when a later line fails, and rethrows the original exception")
        void releasesEveryPreviouslyReservedLineWhenALaterLineFailsAndRethrows() {
            OrderLine line1 = new OrderLine(widget, 2);   // will succeed
            OrderLine line2 = new OrderLine(gadget, 3);   // will succeed
            OrderLine line3 = new OrderLine(gizmo, 100);  // will fail

            // Failure injection: tell the mock to throw on this exact call, regardless
            // of what a real stock ledger would say. This is the scenario that would be
            // awkward to set up with the real Inventory (see class Javadoc above).
            doThrow(new InsufficientStockException("SKU-GIZMO", 100, 0))
                    .when(inventory).reserve("SKU-GIZMO", 100);

            List<OrderLine> lines = List.of(line1, line2, line3);

            // The exception must propagate to the caller UNCHANGED -- OrderService's
            // catch block does `throw e`, not `throw new RuntimeException("wrapped", e)`
            // or swallow-and-return-null. Asserting the concrete type (and that the
            // message still names the failing SKU) is what proves it's the *same*
            // exception object flowing through, not a new one constructed in the catch.
            assertThatThrownBy(() -> orderService.placeOrder(customer, lines))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("SKU-GIZMO");

            // Every line that was ACTUALLY reserved before the failure (line1, line2)
            // must be released again -- this is the "all or nothing" guarantee: a
            // failed placeOrder() call must leave Inventory exactly as it found it.
            verify(inventory).release("SKU-WIDGET", 2);
            verify(inventory).release("SKU-GADGET", 3);

            // The line that FAILED must NOT be released -- reserve() threw before ever
            // mutating stock for SKU-GIZMO (Inventory.reserve()'s documented "no partial
            // decrement" contract, covered directly in InventoryTest), so releasing it
            // here would incorrectly credit stock that was never actually taken.
            verify(inventory, never()).release("SKU-GIZMO", 100);

            // reserve() itself WAS attempted for all three lines -- line 3's attempt is
            // exactly what threw. "Attempted and threw" is different from "never
            // called," and this pins down that OrderService didn't, say, pre-check
            // stock some other way before calling reserve() on line 3.
            verify(inventory).reserve("SKU-WIDGET", 2);
            verify(inventory).reserve("SKU-GADGET", 3);
            verify(inventory).reserve("SKU-GIZMO", 100);

            // BONUS -- ordering: OrderService.placeOrder() tracks successfully-reserved
            // lines in an ArrayDeque used as a stack (push() on success). Iterating that
            // deque front-to-back in the catch block therefore visits the MOST recently
            // reserved line first, so the rollback happens in LIFO order: line2 released
            // before line1. Mockito's InOrder pins that exact sequence down, so a future
            // refactor that silently reverses it (e.g. swapping ArrayDeque for a List
            // iterated in insertion order) would be caught here instead of shipping
            // unnoticed.
            InOrder rollbackOrder = inOrder(inventory);
            rollbackOrder.verify(inventory).release("SKU-GADGET", 3);
            rollbackOrder.verify(inventory).release("SKU-WIDGET", 2);

            // No other interaction with Inventory happened beyond the reserve/release
            // calls already verified above (e.g. nothing accidentally called restock()).
            verifyNoMoreInteractions(inventory);
        }
    }

    @Nested
    @DisplayName("reporting methods (do not touch Inventory)")
    class ReportingMethods {

        @Test
        @DisplayName("totalSpentByImperative and totalSpentByStreams agree for the same input")
        void imperativeAndStreamsTotalSpentAgreeForTheSameInput() {
            // These two methods never call Inventory -- the @Mock above simply goes
            // unused in this test. That is fine under Mockito's strict-stubbing mode
            // (the default with MockitoExtension): strict stubbing flags *stubs that
            // are configured but never invoked* (via when()/doThrow()) as a test
            // smell -- it says nothing about mocks that receive no stubbing at all,
            // which is exactly this case.
            Order order1 = new Order("ORD-1", customer);
            order1.addLine(new OrderLine(widget, 2)); // 20.00

            Order order2 = new Order("ORD-2", customer);
            order2.addLine(new OrderLine(gadget, 1)); // 20.00

            Customer otherCustomer = new Customer("CUST-2", "Alan Turing", "alan@example.com");
            Order otherCustomersOrder = new Order("ORD-3", otherCustomer);
            otherCustomersOrder.addLine(new OrderLine(gizmo, 1)); // must be excluded from customer's total

            List<Order> orders = List.of(order1, order2, otherCustomersOrder);

            BigDecimal imperativeTotal = orderService.totalSpentByImperative(customer, orders);
            BigDecimal streamsTotal = orderService.totalSpentByStreams(customer, orders);

            // Property-style check: two independently written implementations of the
            // same computation (a hand-rolled loop vs. a filter/map/reduce pipeline)
            // must agree on every input, not just happen to match for one example --
            // this is the actual point of java-basics keeping both methods side by
            // side. isEqualByComparingTo (not isEqualTo) sidesteps BigDecimal's
            // scale-sensitive equals().
            assertThat(imperativeTotal).isEqualByComparingTo(streamsTotal);
            assertThat(imperativeTotal).isEqualByComparingTo("40.00"); // 20.00 + 20.00, otherCustomer's order excluded
        }

        @Test
        @DisplayName("ordersByStatus groups orders under their current status, and only that status")
        void ordersByStatusGroupsOrdersByTheirCurrentStatus() {
            Order pendingOrder = new Order("ORD-1", customer);

            Order confirmedOrder = new Order("ORD-2", customer);
            confirmedOrder.transitionTo(OrderStatus.CONFIRMED);

            Order cancelledOrder = new Order("ORD-3", customer);
            cancelledOrder.transitionTo(OrderStatus.CANCELLED);

            Map<OrderStatus, List<Order>> byStatus = orderService.ordersByStatus(
                    List.of(pendingOrder, confirmedOrder, cancelledOrder));

            assertThat(byStatus)
                    .containsEntry(OrderStatus.PENDING, List.of(pendingOrder))
                    .containsEntry(OrderStatus.CONFIRMED, List.of(confirmedOrder))
                    .containsEntry(OrderStatus.CANCELLED, List.of(cancelledOrder));
            // Collectors.groupingBy() only creates keys for statuses actually present in
            // the input -- SHIPPED/DELIVERED must not show up as empty-list entries.
            assertThat(byStatus).doesNotContainKey(OrderStatus.SHIPPED);
            assertThat(byStatus).doesNotContainKey(OrderStatus.DELIVERED);
        }
    }
}
