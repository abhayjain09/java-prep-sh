# GRASP — General Responsibility Assignment Software Patterns

**Domain used throughout:** the same Order/Inventory system from
[java-basics](../java-basics). Where SOLID.md asks "is this class/interface
shaped well?", GRASP asks the question that comes BEFORE that: "which class
should this piece of behavior live on in the first place?" The two are
complementary, not competing — GRASP guides initial responsibility
assignment; SOLID gives you criteria to evaluate/refactor the result.

Companion files: [SOLID.md](SOLID.md) · [gof/](src/main/java/com/interviewprep/orders/patterns) ·
[README.md](README.md) · [EXPLANATION.md](EXPLANATION.md) ·
[EXERCISES.md](EXERCISES.md) · [INTERVIEW.md](INTERVIEW.md)

### Why this matters for interviews
GRASP is asked about far less often BY NAME than SOLID or GoF patterns, but
the underlying QUESTIONS ("why does this method live on this class and not
that one?") come up constantly in code review and system-design interviews,
even when the interviewer never says "GRASP." Being able to name and reason
from these principles gives you vocabulary to justify design decisions on
the spot rather than defending them with "it felt right."

---

## 1. Information Expert

**Principle:** assign a responsibility to the class that has the
INFORMATION needed to fulfill it.

**Domain example:** `Order.totalAmount()` lives on `Order` (which holds the
`List<OrderLine>`), not on some external `OrderCalculator` that would need
`Order` to expose its internal list just to sum it. `Order` is the
Information Expert for "what is this order's total" because it already
holds every `OrderLine`, each of which (via `OrderLine.lineTotal()`) is
itself the Information Expert for "what does this one line cost" — it holds
the `Product` (price) and `quantity`.

```java
// Order.java — the Information Expert for its own total.
public BigDecimal totalAmount() {
    return lines.stream().map(OrderLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
}
```

**Why it matters:** putting `totalAmount()` calculation logic in an
external class instead would force `Order` to expose its line list more
broadly than it otherwise needs to (weakening encapsulation — see
java-basics/README.md's `getLines()` discussion), purely so some OTHER
class could do a computation `Order` was already perfectly positioned to do
itself.

---

## 2. Creator

**Principle:** class `B` should be responsible for creating instances of
class `A` if `B` contains/aggregates `A`, records `A`, closely uses `A`, or
has the data needed to initialize `A`.

**Domain example:** `Order.addLine(OrderLine line)` — `Order` doesn't
CREATE the `OrderLine` itself in this codebase (the caller constructs it
and passes it in), but `Order` IS the natural home for the `List<OrderLine>`
aggregation per the Creator principle's "aggregates A" clause, which is why
`lines` lives on `Order` rather than, say, a free-floating
`OrderLineRegistry` with no containing `Order` reference.

A more direct example: [`OrderRequestBuilder`](src/main/java/com/interviewprep/orders/patterns/creational/builder/OrderRequestBuilder.java)
is the Creator for `OrderRequest` — it holds ALL the data needed
(`customer`, `lines`, `note`, `discountCode`, flags) to construct one
validly, which is exactly the Creator principle's "has the initializing
data" clause. This is also why the Builder pattern (see gof/creational/builder)
so often IS the GRASP Creator in practice — a dedicated builder object
exists specifically because it has accumulated the data needed to create
the target object.

---

## 3. Controller

**Principle:** assign the responsibility of handling a system event to a
non-UI class representing either the overall system, or a use-case
scenario.

**Domain example:** `OrderService` (java-basics) is the Controller for the
"place an order" use case — it receives the request (customer + requested
lines), coordinates `Inventory` and `Order` to fulfill it, and is what a
future REST controller (Module 5) delegates to, rather than a `@RestController`
method containing all of `placeOrder()`'s reservation/rollback logic itself.
[`CheckoutFacade`](src/main/java/com/interviewprep/orders/patterns/structural/facade/CheckoutFacade.java)
is a second, closely related example — a Controller for the "checkout"
use case that coordinates Inventory, PricingService, and NotificationService.

**Common mistake this principle prevents:** putting use-case orchestration
logic directly in a UI/web layer class (a `@RestController` method with 40
lines of business logic) instead of a dedicated Controller/Service class —
this couples business logic to the delivery mechanism (REST today, maybe a
message-queue consumer tomorrow) and makes the logic untestable without
spinning up the web layer.

---

## 4. Low Coupling

**Principle:** minimize dependencies between classes — favor designs where
a change in one class has minimal ripple effect on others.

**Domain example:** `Inventory` knows NOTHING about `Order`, `OrderLine`, or
`Customer` — it only knows SKUs (strings) and quantities (ints). This is
deliberate low coupling: `OrderService` is what depends on BOTH `Inventory`
and the order domain classes, so `Inventory` itself can be reused, tested,
and evolved (e.g. swapped for a distributed/multi-warehouse implementation)
completely independently of how orders are modeled.

Directly demonstrated (as an anti-pattern to contrast against) in
[gof/behavioral/mediator](src/main/java/com/interviewprep/orders/patterns/behavioral/mediator) —
`NaiveDirectColleagueCommunication` shows colleagues coupled directly to
each other's concrete APIs; `CheckoutMediator` fixes it by having every
colleague depend on just the mediator interface.

---

## 5. High Cohesion

**Principle:** keep a class's responsibilities strongly related and
focused — the counterbalance to Low Coupling (it's easy to achieve low
coupling by scattering unrelated logic into many tiny classes, which then
have LOW cohesion internally between call sites that need several of them
together for one coherent task).

**Domain example:** `OrderStatus` (java-basics) has HIGH cohesion — every
method on it (`canTransitionTo`, `legalNextStates`) is about exactly one
thing: the lifecycle state machine. Compare with the SOLID.md `OrderManager`
violation (validation + pricing + persistence + notification in one class)
— that class has LOW cohesion: its four responsibilities have nothing to do
with each other, they just happen to all run during order creation.

**The balancing act (a real senior-interview discussion point):** SRP
(SOLID) and High Cohesion (GRASP) point the same direction, but taken to an
extreme they conflict with avoiding over-fragmentation — five one-method
classes that must ALWAYS be used together to accomplish anything coherent
have technically satisfied "one reason to change each" while creating a
new problem: understanding "how is an order actually created" now requires
opening five files instead of one focused one. Good design finds the
middle: group what changes together and is conceptually one idea (high
cohesion), separate what changes for different reasons and belongs to
different stakeholders (SRP/low coupling).

---

## 6. Polymorphism

**Principle:** when related alternatives vary by type, assign responsibility
for the varying behavior to the types themselves (via a common interface
and overriding), rather than to a client that branches with
if/else-or-switch on a type code.

**Domain example:** every "Naive___" class with an if/else on a type STRING
in this module is exactly the ANTI-pattern this principle warns against —
see [`NaiveDiscountCalculator`](src/main/java/com/interviewprep/orders/patterns/behavioral/strategy/NaiveDiscountCalculator.java)
(Strategy), [`NaivePaymentProcessorCreation`](src/main/java/com/interviewprep/orders/patterns/creational/factorymethod/NaivePaymentProcessorCreation.java)
(Factory Method), and [`NaiveInstanceofOperations`](src/main/java/com/interviewprep/orders/patterns/behavioral/visitor/NaiveInstanceofOperations.java)
(Visitor). Each "correct" counterpart applies Polymorphism: `DiscountStrategy`
implementations, `PaymentProcessor` implementations, and
`OrderComponentVisitor` implementations all push the varying behavior into
the TYPE, resolved automatically by dynamic dispatch, instead of a
central branch that must be edited for every new variant.

This is, informally, GRASP's name for the mechanism most GoF behavioral
patterns are built on — see SOLID.md's OCP section for the same idea from
the "closed for modification" angle.

---

## 7. Pure Fabrication

**Principle:** when no domain concept naturally owns a responsibility,
invent ("fabricate") a class purely for design convenience/cohesion —
it doesn't represent a real-world domain object, but its existence keeps
domain classes clean.

**Domain example:** `OrderRepository` (a persistence-focused class,
previewed in SOLID.md's SRP fix) is a Pure Fabrication — there is no
real-world "order repository" concept a business person would recognize;
it exists purely to keep persistence logic (a technical concern) OUT of
`Order` (a domain concept). Likewise,
[`PaymentGatewayAdapter`](src/main/java/com/interviewprep/orders/patterns/structural/adapter/PaymentGatewayAdapter.java)
is a Pure Fabrication — no business stakeholder thinks in terms of "the
adapter class"; it exists purely to keep translation logic isolated.

**Why this matters:** without permission to fabricate classes, engineers
under pressure to "keep it domain-driven" sometimes cram technical
responsibilities (SQL, HTTP client calls, third-party SDK glue) directly
into domain classes like `Order`, coupling a pure business concept to
infrastructure concerns. Pure Fabrication is the GRASP principle that
explicitly sanctions NOT doing that.

---

## 8. Indirection

**Principle:** assign responsibility to an intermediate object to
decouple two other components that would otherwise depend on each other
directly.

**Domain example:** [`OrderStatusPublisher`](src/main/java/com/interviewprep/orders/patterns/behavioral/observer/OrderStatusPublisher.java)
is Indirection between "code that changes an order's status" and "code
that reacts to a status change" (email/SMS/audit log) — neither side talks
to the other directly; both talk to the publisher. The Adapter, Facade,
Mediator, and Proxy GoF patterns are ALL, structurally, applications of
Indirection: an object inserted between two other objects specifically to
avoid a direct dependency.

---

## 9. Protected Variations

**Principle:** identify points of predicted variation and wrap a stable
interface around them, so change at that point doesn't ripple outward.

**Domain example:** [`OrderDocumentFactory`](src/main/java/com/interviewprep/orders/patterns/creational/abstractfactory/OrderDocumentFactory.java)
protects checkout code from variation in "which region's document rules
apply" — checkout code depends on the stable `OrderDocumentFactory`
interface; US-vs-EU variation is entirely hidden behind it. Similarly,
`PaymentProcessor` (used across several patterns in this module) protects
checkout code from variation in "which payment provider is behind this
charge" — a new provider is a new implementation, with zero ripple into
code that only depends on the interface.

**Relationship to Open/Closed (SOLID):** Protected Variations is
essentially GRASP's more general statement of the same idea OCP names
specifically for classes — "wrap the unstable part behind something
stable" applies to interfaces, APIs, database schemas, and message formats,
not just class hierarchies.

---

## Summary table

| Principle | Answers the question | Domain example |
|---|---|---|
| Information Expert | Who has the data to do this? | `Order.totalAmount()` |
| Creator | Who has what's needed to build this? | `OrderRequestBuilder` |
| Controller | Who receives this use-case request? | `OrderService`, `CheckoutFacade` |
| Low Coupling | How do I minimize ripple effects? | `Inventory` knows nothing about `Order` |
| High Cohesion | Does this class do ONE coherent thing? | `OrderStatus`'s state-machine-only focus |
| Polymorphism | How do I avoid branching on type? | `DiscountStrategy`, `PaymentProcessor` |
| Pure Fabrication | What if no domain class fits? | `OrderRepository`, `PaymentGatewayAdapter` |
| Indirection | How do I decouple two components? | `OrderStatusPublisher`, Mediator/Facade/Adapter |
| Protected Variations | How do I isolate an unstable point? | `OrderDocumentFactory`, `PaymentProcessor` |

Next: [gof/creational](src/main/java/com/interviewprep/orders/patterns/creational),
[gof/structural](src/main/java/com/interviewprep/orders/patterns/structural),
[gof/behavioral](src/main/java/com/interviewprep/orders/patterns/behavioral) —
all 23 GoF patterns, each of which is a specific, named technique for
achieving one or more of the SOLID/GRASP principles above in a particular
recurring situation.
