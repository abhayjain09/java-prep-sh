# LLD — Saga Orchestrator (Order Placement)

A deeper low-level design for the single component this module treats as its centerpiece (README.md Section 6): an orchestration-based Saga Orchestrator that generalizes `java-basics`' `OrderService.placeOrder()` rollback loop across separate Order, Inventory, Payment, and Shipping services.

**Code:** [src/main/java/com/interviewprep/orders/saga/](src/main/java/com/interviewprep/orders/saga/) — illustrative, written and carefully re-read for structural correctness, **not compiled** (this sandbox has no `java`/`javac`; see the repo-wide constraint noted in every file's header comment). Package `com.interviewprep.orders.saga`, with order-placement-specific wiring under `com.interviewprep.orders.saga.orderplacement`.

## Class diagram

```mermaid
classDiagram
    class SagaOrchestrator {
        -List~SagaStep~ steps
        +SagaOrchestrator(List~SagaStep~ steps)
        +run(SagaContext context) SagaState
    }

    class SagaStep {
        <<interface>>
        +name() String
        +execute(SagaContext context)
        +compensate(SagaContext context)
    }

    class SagaContext {
        -String sagaId
        -Map~String, Object~ attributes
        +sagaId() String
        +put(String key, Object value)
        +get(String key, Class~T~ type) T
        +has(String key) boolean
    }

    class SagaState {
        <<enumeration>>
        STARTED
        IN_PROGRESS
        COMPLETED
        COMPENSATING
        FAILED
    }

    class CompensationRegistry {
        -Deque~SagaStep~ completedSteps
        +recordCompleted(SagaStep step)
        +isEmpty() boolean
        +compensateAll(SagaContext context, CompensationFailureHandler handler)
    }

    class SagaStepException {
        -String stepName
        +stepName() String
    }

    class CreateOrderStep {
        -OrderRepository orderRepository
        +execute(SagaContext context)
        +compensate(SagaContext context)
    }
    class ReserveInventoryStep {
        -InventoryServiceClient inventoryClient
        +execute(SagaContext context)
        +compensate(SagaContext context)
    }
    class ChargePaymentStep {
        -PaymentServiceClient paymentClient
        +execute(SagaContext context)
        +compensate(SagaContext context)
    }
    class ArrangeShippingStep {
        -ShippingServiceClient shippingClient
        +execute(SagaContext context)
        +compensate(SagaContext context)
    }
    class ConfirmOrderStep {
        -OrderRepository orderRepository
        +execute(SagaContext context)
        +compensate(SagaContext context)
    }

    class OrderRepository {
        <<interface>>
        +createPendingOrder(sagaId, customerId, lines, totalAmount) String
        +transitionTo(orderId, targetStatus)
    }
    class InventoryServiceClient {
        <<interface>>
        +reserve(sagaId, lines) String
        +release(reservationId)
    }
    class PaymentServiceClient {
        <<interface>>
        +charge(sagaId, customerId, amount) String
        +refund(paymentId)
    }
    class ShippingServiceClient {
        <<interface>>
        +scheduleShipment(sagaId, orderId, lines) String
        +cancelShipment(shipmentId)
    }

    SagaOrchestrator "1" o-- "many" SagaStep : executes in order
    SagaOrchestrator ..> SagaContext : passes to each step
    SagaOrchestrator ..> SagaState : returns
    SagaOrchestrator ..> CompensationRegistry : delegates rollback to
    SagaOrchestrator ..> SagaStepException : throws on failure
    CompensationRegistry "1" o-- "many" SagaStep : tracks completed
    CompensationRegistry ..> SagaContext : passes to compensate()

    SagaStep <|.. CreateOrderStep
    SagaStep <|.. ReserveInventoryStep
    SagaStep <|.. ChargePaymentStep
    SagaStep <|.. ArrangeShippingStep
    SagaStep <|.. ConfirmOrderStep

    CreateOrderStep --> OrderRepository : uses
    ConfirmOrderStep --> OrderRepository : uses
    ReserveInventoryStep --> InventoryServiceClient : uses
    ChargePaymentStep --> PaymentServiceClient : uses
    ArrangeShippingStep --> ShippingServiceClient : uses
```

## Design rationale — walking the diagram

- **`SagaOrchestrator`** holds an ordered `List<SagaStep>` and is the only class that knows the *sequence* of the order-placement flow (`CreateOrder → ReserveInventory → ChargePayment → ArrangeShipping → ConfirmOrder`). This centralization is exactly what "orchestration" means as opposed to "choreography" (README.md Section 6) — read `OrderPlacementSagaFactory.create()` and the whole flow is visible in one place.
- **`SagaStep`** is a narrow interface (`name`, `execute`, `compensate`) — every step, regardless of which downstream service it calls, looks identical to the orchestrator. This is what lets `SagaOrchestrator` be written once and reused for any saga (order placement today; a future returns/refund saga, or a supplier-restocking saga, could reuse the exact same `SagaOrchestrator`/`CompensationRegistry` classes with a different `List<SagaStep>`).
- **`SagaContext`** is the one mutable object threaded through every step — the distributed-saga replacement for local variables/method parameters that a single-process method body would otherwise use directly (see its Javadoc for the direct mapping).
- **`CompensationRegistry`** is deliberately a separate class from `SagaOrchestrator`, not an inline `Deque` local variable, specifically because — unlike `java-basics`' `reserved` local variable, which can safely vanish when `placeOrder()` returns or throws — a real orchestrator's "what completed so far" list must be durable enough to survive an orchestrator crash mid-saga. Making it its own class is what makes that persistence concern addressable in one place (a real implementation would back it with a database table) without entangling it into `SagaOrchestrator`'s control-flow logic.
- **`SagaStepException`** always wraps the *original* failure as its cause (never swallowed), mirroring `java-basics`' `InsufficientStockException` handling and the repo-wide "never lose the cause" rule from that module's Exception Handling section.
- **`OrderRepository` vs. the three `*ServiceClient` interfaces** is a deliberate asymmetry, not an oversight: per the HLD ([../diagrams/hld-microservices.md](../diagrams/hld-microservices.md)), the orchestrator lives inside the Order Service, which owns `Order` directly — so `CreateOrderStep`/`ConfirmOrderStep` use a local repository, exactly like `java-basics`' `OrderService` constructs and mutates `Order` in-process today. Only steps crossing into a genuinely different service (Inventory, Payment, Shipping) go through a network-client interface with the idempotency contract that implies.

## Where this maps back to `java-basics`

| `java-basics` (Module 1, in-process) | This LLD (Module 13, distributed) |
|---|---|
| `Deque<OrderLine> reserved` (local variable) | `CompensationRegistry` (own class, durable) |
| `try { ... } catch (InsufficientStockException e) { ... }` | `SagaOrchestrator.run()`'s try/catch per step |
| `inventory.reserve(sku, qty)` | `ReserveInventoryStep.execute()` calling `InventoryServiceClient.reserve(...)` |
| `inventory.release(sku, qty)` | `ReserveInventoryStep.compensate()` calling `InventoryServiceClient.release(...)` |
| `throw e;` (preserve original exception) | `throw new SagaStepException(step.name(), stepFailure);` (cause preserved) |
| `Order.transitionTo(OrderStatus)` | `OrderRepository.transitionTo(orderId, targetStatus)`, called from `CreateOrderStep`/`ConfirmOrderStep` |

See [../EXPLANATION.md](../EXPLANATION.md) for a line-by-line walkthrough of the actual Java files, and [../diagrams/saga-happy-path.md](../diagrams/saga-happy-path.md) / [../diagrams/saga-compensation-path.md](../diagrams/saga-compensation-path.md) for this same design as an executed sequence.
