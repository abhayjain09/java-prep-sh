package com.interviewprep.orders.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link Inventory} in isolation -- no mocks needed here at all,
 * because Inventory has no collaborators of its own (it's a leaf class: just a
 * {@code Map<String, Integer>} behind a small, invariant-enforcing API). This is
 * the simplest tier of the test pyramid (see testing/diagrams/test-pyramid.md):
 * fast, deterministic, no I/O, exercising real production code directly.
 *
 * WHY A FRESH Inventory PER TEST (@BeforeEach, not a shared static field): Inventory
 * is mutable. If one Inventory instance were reused across test methods, the order
 * tests happen to run in (JUnit does not guarantee declaration order) could make a
 * test pass or fail depending on what an earlier test left behind -- a classic
 * source of flaky, order-dependent tests. Each test gets a brand-new, empty
 * Inventory so every test is self-contained and can be run alone, in any order,
 * or repeatedly, with identical results.
 */
class InventoryTest {

    private Inventory inventory;

    @BeforeEach
    void setUp() {
        inventory = new Inventory();
    }

    @Nested
    @DisplayName("reserve()")
    class ReserveTests {

        @Test
        @DisplayName("reduces stock by the reserved quantity when enough is available")
        void reservesStockWhenEnoughIsAvailable() {
            inventory.restock("SKU-WIDGET", 10);

            inventory.reserve("SKU-WIDGET", 4);

            assertThat(inventory.stockOf("SKU-WIDGET")).isEqualTo(6);
        }

        @Test
        @DisplayName("throws InsufficientStockException and leaves stock untouched when not enough is available")
        void throwsInsufficientStockExceptionWhenNotEnoughAvailable() {
            inventory.restock("SKU-WIDGET", 3);

            // assertThatThrownBy (AssertJ) reads left-to-right as a sentence -- "calling
            // this should throw X" -- and lets us chain further assertions on the
            // exception itself (message, type) in the same fluent expression, unlike
            // JUnit's assertThrows(...) which returns the exception for separate
            // follow-up statements. Either is correct; this file demonstrates AssertJ's
            // style since the module introduces it as the preferred assertion library.
            assertThatThrownBy(() -> inventory.reserve("SKU-WIDGET", 5))
                    .isInstanceOf(InsufficientStockException.class)
                    .satisfies(thrown -> {
                        InsufficientStockException ex = (InsufficientStockException) thrown;
                        // Asserting on the exception's own accessor methods, not on
                        // getMessage() text -- see InsufficientStockException's Javadoc
                        // in java-basics: "never parse an exception message to extract
                        // data; expose fields instead." A test that parsed the message
                        // string would be exactly the anti-pattern that Javadoc warns
                        // production callers away from.
                        assertThat(ex.sku()).isEqualTo("SKU-WIDGET");
                        assertThat(ex.requested()).isEqualTo(5);
                        assertThat(ex.available()).isEqualTo(3);
                    });

            // Inventory.reserve()'s Javadoc promises it "does NOT partially decrement"
            // on failure -- this assertion is what actually pins that contract down.
            // Without it, a bug that decremented stock *before* checking availability
            // would still make the test above pass (the exception is still thrown) while
            // silently corrupting inventory state.
            assertThat(inventory.stockOf("SKU-WIDGET")).isEqualTo(3);
        }

        @Test
        @DisplayName("boundary: reserving exactly the available amount succeeds and leaves zero stock")
        void reservingExactlyAvailableStockSucceedsAndLeavesZero() {
            // Boundary case deliberately isolated into its own test: available == requested
            // is the edge between "succeeds" (available > requested, covered above) and
            // "throws" (available < requested, covered above). Off-by-one bugs in
            // Inventory.reserve()'s `available < quantity` check (e.g. accidentally
            // written as `<=`) would only be caught by testing exactly this boundary.
            inventory.restock("SKU-WIDGET", 5);

            inventory.reserve("SKU-WIDGET", 5);

            assertThat(inventory.stockOf("SKU-WIDGET")).isZero();
        }
    }

    @Nested
    @DisplayName("release() and restock()")
    class ReleaseAndRestockTests {

        @Test
        @DisplayName("restock() adds to existing stock rather than replacing it")
        void restockAddsToExistingStock() {
            inventory.restock("SKU-WIDGET", 10);
            inventory.restock("SKU-WIDGET", 5);

            // Exercises Inventory.restock()'s use of Map.merge(sku, quantity, Integer::sum)
            // -- a naive `put` implementation would leave this at 5, not 15.
            assertThat(inventory.stockOf("SKU-WIDGET")).isEqualTo(15);
        }

        @Test
        @DisplayName("release() returns previously reserved stock")
        void releaseReturnsPreviouslyReservedStock() {
            inventory.restock("SKU-WIDGET", 10);
            inventory.reserve("SKU-WIDGET", 4);

            inventory.release("SKU-WIDGET", 4);

            // release() is what OrderService.placeOrder()'s rollback path calls on
            // partial failure (see OrderServiceTest in service/) -- this test proves the
            // primitive it relies on actually undoes a reserve() one-for-one in isolation,
            // separate from OrderService's own orchestration logic.
            assertThat(inventory.stockOf("SKU-WIDGET")).isEqualTo(10);
        }

        @Test
        @DisplayName("release() on a SKU with no prior stock still credits it (no floor at a prior balance)")
        void releaseOnUnknownSkuCreditsItFromZero() {
            inventory.release("SKU-NEVER-STOCKED", 2);

            assertThat(inventory.stockOf("SKU-NEVER-STOCKED")).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("stockOf()")
    class StockOfTests {

        @Test
        @DisplayName("returns 0 for a SKU that was never restocked, instead of throwing or returning null")
        void unknownSkuDefaultsToZero() {
            // Exercises Inventory.stockOf()'s use of getOrDefault(sku, 0) -- the whole
            // point (per the class's own Javadoc) is that an unqueried SKU is simply
            // zero stock, not a special "unknown SKU" case every caller has to branch on.
            assertThat(inventory.stockOf("SKU-NEVER-SEEN")).isZero();
        }
    }
}
