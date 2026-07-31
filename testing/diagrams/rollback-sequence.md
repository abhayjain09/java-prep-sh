# Sequence Diagram — `OrderServiceTest`'s mocked rollback scenario

This traces exactly what happens inside
[`OrderServiceTest.PlaceOrderRollback.releasesEveryPreviouslyReservedLineWhenALaterLineFailsAndRethrows()`](../src/test/java/com/interviewprep/orders/service/OrderServiceTest.java),
the centerpiece test of this module. `Inventory` here is a **Mockito mock**, not the
real class from `java-basics` — everything it "does" below is exactly what the test
told it to do via `doThrow(...)` (for the third call) or Mockito's default
do-nothing behavior for an unstubbed void method (for the first two calls).

```mermaid
sequenceDiagram
    participant T as OrderServiceTest
    participant S as OrderService (real)
    participant I as Inventory (Mockito mock)

    Note over T: doThrow(InsufficientStockException)<br/>.when(inventory).reserve("SKU-GIZMO", 100)

    T->>S: placeOrder(customer, [line1, line2, line3])
    activate S

    S->>I: reserve("SKU-WIDGET", 2)
    activate I
    I-->>S: (does nothing — unstubbed void call succeeds)
    deactivate I
    Note right of S: reserved.push(line1)

    S->>I: reserve("SKU-GADGET", 3)
    activate I
    I-->>S: (does nothing — unstubbed void call succeeds)
    deactivate I
    Note right of S: reserved.push(line2)

    S->>I: reserve("SKU-GIZMO", 100)
    activate I
    I-->>S: throws InsufficientStockException
    deactivate I
    Note right of S: caught by placeOrder's catch block.<br/>reserved = [line2, line1] (LIFO, most-recent first)

    S->>I: release("SKU-GADGET", 3)
    activate I
    I-->>S: (does nothing)
    deactivate I
    Note right of S: rollback for line2 (pushed last, released first)

    S->>I: release("SKU-WIDGET", 2)
    activate I
    I-->>S: (does nothing)
    deactivate I
    Note right of S: rollback for line1

    S-->>T: throws InsufficientStockException (same instance — "throw e", not wrapped)
    deactivate S

    Note over T: assertThatThrownBy(...).isInstanceOf(InsufficientStockException.class)<br/>verify(inventory).release("SKU-GADGET", 3)<br/>verify(inventory).release("SKU-WIDGET", 2)<br/>verify(inventory, never()).release("SKU-GIZMO", 100)<br/>InOrder: release(GADGET) before release(WIDGET)
```

## What this diagram makes visible that the code alone doesn't

- **The rollback order is LIFO, not FIFO.** `OrderService` tracks successfully
  reserved lines in an `ArrayDeque` used as a stack (`push()` on success). Iterating
  it in the `catch` block therefore visits the *most recently* reserved line first —
  line2 (gadget) is released before line1 (widget), the reverse of reservation
  order. `OrderServiceTest` pins this down with a Mockito `InOrder` verification
  specifically so a refactor that silently reverses this order gets caught.
- **The failing line is never released.** `reserve("SKU-GIZMO", 100)` throws
  *before* `Inventory` would have mutated any state for that SKU — so it's correctly
  excluded from the rollback loop, and the test asserts `release()` is never called
  for it (`verify(inventory, never())...`). Releasing it would double-credit stock
  that was never actually taken.
- **The exception instance is preserved, not wrapped.** The arrow back to
  `OrderServiceTest` at the bottom shows the *original* `InsufficientStockException`
  propagating unchanged — `OrderService`'s catch block re-throws it (`throw e`)
  rather than catching and wrapping it in a new exception, which would destroy the
  original stack trace (see `java-basics/README.md`'s Exception Handling section).
