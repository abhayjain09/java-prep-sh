# Module 13 — Explanation

This module is diagram/doc-heavy rather than code-heavy (per the delivery plan for this module), so this file focuses on two things: a line-by-line walkthrough of the LLD skeleton in [lld/src/](lld/src/), and the reasoning behind each diagram's structure — not a prose restatement of the README's conceptual sections, which already explain the "why" for each pattern.

## Walking the Saga Orchestrator code

Read the files in this order — same "dependencies first" convention `java-basics/EXPLANATION.md` uses.

### `saga/SagaContext.java`
```java
private final Map<String, Object> attributes = new HashMap<>();
```
An untyped `Map<String, Object>` bag, deliberately less type-safe than `java-basics`' domain classes (which lean hard on records and generics for compile-time safety). This is a conscious trade-off specific to this component: the orchestrator has to carry *arbitrary* data produced by *arbitrary* steps (a reservation ID here, a payment ID there) without knowing the concrete step types at compile time — the alternative (a strongly-typed context per saga) would mean writing a new context class for every saga definition, which doesn't scale as more sagas are added. The cost is pushed to each step's `get(key, Class<T>)` call, which is where a typo'd key or wrong expected type would surface — at runtime, not compile time. This is flagged explicitly rather than glossed over, because "generics/type-safety at compile time vs. runtime" is exactly the kind of trade-off this repo has trained you to name (see `java-basics/README.md` Section 3 on generics) — here it's being made in the *other* direction for a good, explained reason, not by oversight.

### `saga/SagaState.java`
A five-value enum, structurally identical in spirit to `java-basics`' `OrderStatus` (small, closed, exhaustive-switchable) — but modeling a different state machine (saga execution progress, not order business status). Worth internalizing that these two state machines are related but distinct: `SagaState.FAILED` (the saga finished compensating) is what *causes* the underlying `Order`'s `OrderStatus` to become `CANCELLED`, but they're not the same enum and shouldn't be conflated — a saga orchestrator's own execution status and the business entity's status are different concerns that happen to be causally linked.

### `saga/SagaStep.java`
```java
void execute(SagaContext context) throws Exception;
void compensate(SagaContext context);
```
Notice `execute` is declared `throws Exception` (checked) while `compensate` is not. This is intentional: `execute` implementations wrap arbitrary remote-call failures (network exceptions, timeouts, whatever the underlying HTTP/gRPC client throws) and the orchestrator's job is explicitly to catch *any* failure and trigger compensation — declaring `throws Exception` keeps that contract honest instead of forcing every implementation to wrap checked exceptions in an unchecked one just to satisfy an overly narrow interface signature. `compensate` doesn't declare `throws Exception` because `SagaOrchestrator`/`CompensationRegistry` catch `Exception` around each call anyway (see `CompensationRegistry.compensateAll`) — but implementations are still free to throw unchecked exceptions from it, and the registry handles that.

### `saga/SagaStepException.java`
Same "unchecked, single designated handler downstream, cause never swallowed" reasoning as `java-basics`' `InsufficientStockException` — see that class's Javadoc for the full argument, which is directly cited in this class's own Javadoc rather than re-argued from scratch.

### `saga/CompensationRegistry.java`
The single most important class to compare directly against `java-basics`' `OrderService.placeOrder()`:
```java
private final Deque<SagaStep> completedSteps = new ArrayDeque<>();
public void recordCompleted(SagaStep step) { completedSteps.push(step); }
```
This is `Deque<OrderLine> reserved = new ArrayDeque<>(); reserved.push(line);` from Module 1, verbatim in structure, just holding `SagaStep` instead of `OrderLine`. The `compensateAll` method's for-each loop over `completedSteps` iterates head-to-tail, which for an `ArrayDeque` used via `push()` (which inserts at the head) yields most-recently-pushed-first — exactly the reverse-of-completion-order semantics both Module 1's rollback loop and this class need, and exactly why `ArrayDeque`-as-a-stack (not, say, `ArrayList` iterated with an index) was the right collection choice in both places.

The `CompensationFailureHandler` functional interface is new relative to Module 1 — Module 1's rollback loop has no equivalent, because `Inventory.release()` calling an in-process method essentially can't fail in the way a network call to a remote Inventory *service* can. This is the concrete code-level manifestation of README Section 6's "compensations are not free undo" trade-off.

### `saga/SagaOrchestrator.java`
```java
for (SagaStep step : steps) {
    state = SagaState.IN_PROGRESS;
    try {
        step.execute(context);
        registry.recordCompleted(step);
    } catch (Exception stepFailure) {
        state = SagaState.COMPENSATING;
        registry.compensateAll(context, this::logCompensationFailure);
        state = SagaState.FAILED;
        throw new SagaStepException(step.name(), stepFailure);
    }
}
```
Read this side by side with Module 1's `placeOrder()` try/catch — it's the same shape (try each step, record success, on failure roll back everything recorded so far, re-throw preserving the original cause) generalized from one `for` loop over `OrderLine`s to one `for` loop over `SagaStep`s. The extra machinery here (`SagaState` transitions, `CompensationRegistry` as its own class, `SagaStepException` wrapping) exists entirely to handle the concerns Module 1's single-process version didn't have to: multiple heterogeneous remote collaborators, and a need to track/persist progress that survives longer than one method call's stack frame.

### `saga/orderplacement/*`
The five concrete steps (`CreateOrderStep`, `ReserveInventoryStep`, `ChargePaymentStep`, `ArrangeShippingStep`, `ConfirmOrderStep`) each follow an identical shape: `execute()` reads inputs from the `SagaContext`, calls one collaborator, writes an ID back into the context for its own `compensate()` to use later. The asymmetry between `OrderRepository` (a local interface — `CreateOrderStep`/`ConfirmOrderStep` use it) and the three `*ServiceClient` interfaces (remote — used by the other three steps) is explained in [lld/saga-orchestrator.md](lld/saga-orchestrator.md)'s "Design rationale" section and reflects the HLD's assumption that the orchestrator runs inside the Order service, which owns `Order` directly.

`OrderPlacementSagaFactory.placeOrder(...)` is the actual call site — compare its four lines (build a `SagaContext`, populate it, call `orchestrator.run(context)`) against Module 1's `OrderService.placeOrder(Customer, List<OrderLine>)` one-line call signature. The distributed version needs an explicit context-building step precisely because there's no shared method-call stack frame to implicitly carry `customerId`/`orderLines`/`totalAmount` between steps the way local variables would in a single method body.

## Reasoning behind the diagrams

- **[diagrams/hld-microservices.md](diagrams/hld-microservices.md)** draws "database per service" as a hard rule (four separate DB icons) specifically because that's *why* a saga is necessary at all — a reader should look at this diagram and immediately see there's no shared database to wrap one transaction around, which is the entire justification for everything in Section 6.
- **[diagrams/saga-happy-path.md](diagrams/saga-happy-path.md)** and **[diagrams/saga-compensation-path.md](diagrams/saga-compensation-path.md)** are deliberately near-identical up to the point of divergence (`ChargePaymentStep`) — this is intentional so a reader can literally diff the two mentally and see exactly what changes when a step fails: nothing about the first two steps changes, only what happens *after*.
- **[diagrams/ddd-context-map.md](diagrams/ddd-context-map.md)**'s five relationship arrows are each a different DDD relationship *type* on purpose (Customer/Supplier, ACL, Shared Kernel, Published Language) rather than five generic "depends on" arrows — the goal is that the diagram alone, read carefully, teaches the vocabulary, not just the topology.
- **[diagrams/clean-architecture-layers.md](diagrams/clean-architecture-layers.md)** includes an explicit table mapping this repo's actual folders onto the four layers — because the abstract four-ring diagram alone doesn't make the "java-basics already sits at the innermost ring" claim concrete; the table is what makes it checkable against real files.

See [mock-interview.md](mock-interview.md) for how this same LLD and these same diagrams would actually get drawn and defended live under interview conditions, and [INTERVIEW.md](INTERVIEW.md) for drilling each concept in isolation.
