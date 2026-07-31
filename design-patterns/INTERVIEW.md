# Module 4 — Interview Questions

Organized by topic (SOLID, GRASP, Creational, Structural, Behavioral), then
by level (beginner → intermediate → senior → scenario), matching the format
established in `java-basics/INTERVIEW.md`. Design pattern questions are
asked in essentially every senior Java loop this curriculum targets — most
heavily as live-refactoring exercises at S&P Global, JPMorgan, and Goldman
Sachs ("here's some code, what's wrong, fix it"), and folded into
system-design/code-review rounds at Amazon, Microsoft, Google, Oracle,
Adobe, Salesforce, and Atlassian. Company notes below reflect emphasis, not
exclusivity — these questions show up everywhere in some form.

---

## SOLID

**Beginner:** "What does the Single Responsibility Principle actually mean
— 'one method per class'?"
*Ideal answer:* No — SRP means one REASON TO CHANGE, not one method. A
class can have many methods and still satisfy SRP if they all serve the
same responsibility/actor. The `OrderManager` example in SOLID.md
(validation + pricing + persistence + notification in one class) violates
SRP not because it has multiple methods, but because four UNRELATED
stakeholders (business rules, finance, DBAs, marketing) can each force a
change to it independently.
*Follow-up:* "Can a one-method class still violate SRP?" → Yes, if that one
method does several unrelated things internally (exactly `OrderManager`'s
`createOrder` method before the split) — SRP is about cohesion of purpose,
not method count.

**Intermediate:** "Walk through refactoring a class that validates,
prices, persists, AND emails on order creation."
*Ideal answer:* Extract each concern into its own collaborator
(`OrderValidator`, `TaxCalculator`, `OrderRepository`, `OrderNotifier` —
see SOLID.md's full example) and have a thin coordinating service
(GRASP Controller) call each in turn. Emphasize WHY: each piece becomes
independently testable and independently changeable.
*Follow-up:* "Isn't this over-engineering for a small app?" → Fair
pushback — the answer should acknowledge the trade-off (more files, more
indirection) and argue the split is justified when the concerns actually
change at different rates/owners, not applied reflexively to every class
(this is the High Cohesion vs. over-fragmentation tension in GRASP.md).

**Senior:** "Which GoF patterns most directly serve the Open/Closed
Principle, and what's the common MECHANISM they all rely on?"
*Ideal answer:* Strategy, Decorator, Factory Method/Abstract Factory,
Observer, Template Method, Chain of Responsibility — all replace a branch
on a type/condition with a polymorphic call, so adding new behavior means
adding a new class/implementation rather than editing existing, shipped
code. The mechanism is dynamic dispatch: the JVM resolves WHICH
implementation runs at the call site, so the call site itself never needs
to change when a new implementation is added.
*Follow-up (Goldman Sachs/JPMorgan-style):* "Give a case where OCP is
over-applied and makes code WORSE." → Introducing a Strategy interface with
a SINGLE implementation "for future flexibility" that never materializes —
adds indirection with zero present benefit. YAGNI (You Aren't Gonna Need
It) is a legitimate counter-force to OCP zealotry.

**Scenario:** "A junior engineer wants `OrderLine` to extend `Product` to
avoid retyping the price field. Explain, citing a specific SOLID principle,
why this is wrong — and what it would take for inheritance to be the right
call instead."
*Ideal answer:* Violates Liskov Substitution — an `OrderLine` cannot be
correctly substituted wherever a `Product` is expected (e.g. a catalog
listing), because it carries order-specific `quantity` with no catalog
meaning, and lacks the "this is a sellable, standalone thing" semantics a
`Product` implies. Composition (`OrderLine` HAS a `Product`) is correct.
Inheritance would be right only if `OrderLine` were GENUINELY a more
specific kind of `Product` that could be used anywhere a `Product` is
valid — which it isn't.

---

## GRASP

**Beginner:** "What's the Information Expert principle, in one sentence?"
*Ideal answer:* Put a responsibility on the class that already has the data
needed to fulfill it — `Order.totalAmount()` lives on `Order` because
`Order` already holds every `OrderLine` needed to compute it, rather than
on some external calculator that would need `Order` to expose its internals
just to do the sum.

**Intermediate:** "What's the difference between Low Coupling and High
Cohesion, and why are they usually discussed together?"
*Ideal answer:* Low Coupling minimizes DEPENDENCIES BETWEEN classes; High
Cohesion maximizes how FOCUSED a single class's own responsibilities are.
They're discussed together because they pull in a complementary direction:
naively minimizing coupling (e.g. splitting everything into tiny,
independent classes) can DESTROY cohesion for the call sites that need
several of those tiny pieces together to do one coherent thing. Good design
balances both — group what changes together, separate what doesn't.
*Follow-up:* "Give a domain example of each done well." → Low Coupling:
`Inventory` knows nothing about `Order`/`Customer`, only SKUs and
quantities. High Cohesion: `OrderStatus` — every method on it is about the
lifecycle state machine and nothing else.

**Senior:** "What's Pure Fabrication, and why does GRASP need to explicitly
permit it?"
*Ideal answer:* Pure Fabrication is inventing a class with no real-world
domain counterpart (e.g. `OrderRepository`, `PaymentGatewayAdapter`) purely
to keep domain classes clean of technical concerns. It needs to be
EXPLICITLY sanctioned because a naive "keep it domain-driven" mindset can
pressure engineers to cram persistence/HTTP/SDK glue directly into domain
classes like `Order`, coupling a pure business concept to infrastructure.
Pure Fabrication gives explicit permission to introduce a class that
"isn't a real thing" when doing so is the more cohesive/decoupled choice.
*Follow-up:* "Is a Repository pattern class Pure Fabrication or a domain
concept?" → Pure Fabrication, even though "repository" sounds domain-y — no
business stakeholder describes their job in terms of "the order
repository"; it's a purely technical construct.

**Scenario:** "Two colleagues disagree: one wants a `NotificationSender`
called directly wherever a status change happens; the other wants an
`OrderStatusPublisher` in between. Referee this using GRASP."
*Ideal answer:* This is exactly Indirection — the publisher decouples
"code that changes status" from "code that reacts to it." The direct-call
approach couples the status-changing code to every current AND future
listener; the publisher approach means a new listener (or removing one)
requires zero changes to status-changing code. Side with the
publisher/Indirection unless there is exactly one listener that will ever
exist and is guaranteed never to grow — a genuinely rare guarantee to make
in a real system.

---

## Creational Patterns

**Beginner:** "What's the difference between Factory Method and Abstract
Factory?"
*Ideal answer:* Factory Method creates ONE product, typically via
subclassing/overriding a single creation method (`PaymentProcessorCreator`
in this module). Abstract Factory creates a FAMILY of related products
together through one interface (`OrderDocumentFactory` creating both an
`Invoice` and a `Receipt` that are guaranteed to be from the same region).
Abstract Factory is often implemented USING several Factory Methods
internally — they compose.

**Intermediate:** "Is Singleton an anti-pattern? Defend both sides."
*Ideal answer:* Mechanically, no — a correctly thread-safe Singleton
(holder idiom or enum, see `InventoryRegistry`) is fine engineering.
Architecturally, in a codebase with a DI container, YES it's usually the
wrong call: it hides a dependency (no constructor signature reveals it),
and it leaks shared state between unit tests. The nuanced answer: Singleton
the PATTERN is a reasonable tool for framework-free code with a genuine
process-wide resource; "singleton scope" as a DI CONTAINER FEATURE achieves
the same one-instance guarantee with none of the testability/coupling
costs, which is why senior engineers reach for the latter once a container
exists.
*Follow-up (very common at JPMorgan/Goldman Sachs, where large legacy
codebases often have hand-rolled Singletons predating Spring adoption):*
"How would you migrate a codebase full of hand-rolled Singletons to Spring
DI incrementally?" → Convert each Singleton's `getInstance()` internals
into a `@Bean` method, keep a temporary static `getInstance()` delegating
to a Spring-managed instance during migration (a bridge, not a permanent
fixture), then progressively replace call sites with constructor injection,
removing the static bridge once all callers are converted.

**Senior:** "When would you choose Builder over a constructor with default
parameter objects, or over a fluent setter-based approach without a
`build()` step?"
*Ideal answer:* Builder wins when (1) the target object should be
IMMUTABLE once constructed (setters-without-build leave the door open to
mutation after the fact), (2) there's meaningful VALIDATION that should
happen once, atomically, across all fields together (Builder's `build()` is
a natural single validation point — see `OrderRequestBuilder`), and (3) the
number of optional fields is large enough that positional constructor
params (even bundled into a parameter object) hurt readability at the call
site. For 2-3 optional fields, a well-named parameter record can be
simpler than a full Builder — don't reach for Builder reflexively.

**Scenario:** "Your team's checkout code creates a `Product` object fresh
for every order line during a nightly batch import of 10 million rows
across a 5,000-SKU catalog. Diagnose the performance problem and propose a
GoF fix."
*Ideal answer:* This is the Flyweight scenario directly demonstrated by
`NaiveOrderLineCreation`/`ProductFlyweightFactory` in this module — 10
million redundant immutable `Product` objects instead of 5,000 shared ones.
Fix: a factory (backed by `ConcurrentHashMap.computeIfAbsent` if the import
is multi-threaded) that hands out ONE shared instance per SKU. Emphasize
the PRECONDITION for safety: this only works because `Product` is
immutable — flyweight-sharing a MUTABLE object would let one order line's
mutation corrupt every other line sharing the same instance.

---

## Structural Patterns

**Beginner:** "What's the difference between Adapter and Facade? Both seem
to 'wrap' something."
*Ideal answer:* Adapter makes an INCOMPATIBLE interface usable by
translating it into one your code expects (`PaymentGatewayAdapter`
translating a vendor's cents/status-code API into our `PaymentProcessor`
interface) — it's about a MISMATCH. Facade SIMPLIFIES a multi-step
interaction across MULTIPLE already-compatible subsystems into one
convenient method (`CheckoutFacade` coordinating Inventory + Pricing +
Notification) — it's about REDUCING CALL-SITE COMPLEXITY, not fixing an
incompatibility. A Facade's subsystems don't need adapting; they just need
coordinating.

**Intermediate:** "Explain Bridge with the notification-channel example,
and why it's different from just having `NotificationSender` implementations
without any `OrderNotification` abstraction layer on top."
*Ideal answer:* Bridge exists because there are TWO independent axes of
variation — "kind" of notification (standard, urgent) and "channel"
(email, SMS). Without the abstraction layer (`OrderNotification`/
`UrgentOrderNotification`), you'd need each "kind" to somehow decide which
sender to use itself, OR you'd fold "kind" into the sender hierarchy,
recreating the class-explosion problem (`NaiveNotificationClassExplosion`)
if kind-specific behavior grows. The abstraction layer lets kind-specific
logic (e.g. urgent framing/retry behavior) live independently of channel
choice.
*Follow-up:* "How is this different from Strategy?" → Structurally
similar (both hold a delegate object), but INTENT differs: Strategy is
about swapping an ALGORITHM; Bridge is about decoupling TWO INDEPENDENTLY
VARYING HIERARCHIES so neither multiplies against the other. In practice
the line is blurry and some codebases use the terms loosely — knowing the
INTENT distinction (not just the code shape) is what separates a strong
answer.

**Senior:** "Composite and Visitor are often used together. Explain why,
using the ProductLeaf/ProductBundle example, and name the specific
mechanism (not just 'they work well together') that makes it work."
*Ideal answer:* Composite gives you a uniform tree of `OrderComponent`
(leaves and composites implementing the same interface); Visitor lets you
add NEW OPERATIONS over that tree (tax, shipping weight, and any future
report) without modifying `ProductLeaf`/`ProductBundle` for each one. The
specific mechanism is DOUBLE DISPATCH via `accept(visitor)`: which
`visit(...)` overload runs is resolved by BOTH the element's concrete type
AND the visitor's concrete type, which is what lets a single polymorphic
`accept()` call correctly route to type-specific logic without any
`instanceof`. The trade-off (name it to show you understand the FULL
picture, not just the upside): adding a NEW ELEMENT TYPE to the Composite
tree requires updating the Visitor interface AND every existing visitor —
Visitor optimizes for "stable types, growing operations," which is the
opposite trade-off from a naive `instanceof` chain (easy to add types, hard
to add operations safely).

**Scenario:** "A junior engineer added a role check before every call to
`inventory.restock()` across the codebase. A security audit found one
caller missing the check. How do you fix this so it can't happen again, and
what pattern does your fix use?"
*Ideal answer:* This is the Proxy scenario (`NaiveUncontrolledAccess` /
`SecuredInventoryProxy` in this module) — a Protection Proxy implementing
the same interface as the real `Inventory`, performing the role check
centrally, handed to callers INSTEAD of the real instance. The structural
guarantee: no caller holding the proxy can reach `restock()` without
passing the check, because the check isn't the caller's responsibility
anymore — it's not "remember to add a check," it's "there is no path that
skips it."

---

## Behavioral Patterns

**Beginner:** "What's the classic anti-pattern Strategy replaces, and what
does the fix look like structurally?"
*Ideal answer:* A long if/else or switch keyed on a raw type string,
selecting which ALGORITHM to run (`NaiveDiscountCalculator`'s
`"PERCENTAGE"`/`"FLAT"` branches). The fix: a common interface
(`DiscountStrategy`) with one implementation per algorithm, and a context
object (`PricingContext`) holding a reference to whichever one applies —
selection becomes "which object do I hold," not "which branch do I take."

**Intermediate:** "Explain the difference between Observer and Mediator —
both seem to be about decoupling communication between objects."
*Ideal answer:* Observer is ONE-TO-MANY and UNIDIRECTIONAL: a subject
broadcasts an event to any number of subscribers, who don't talk back
through the same channel (`OrderStatusPublisher` → email/SMS/audit
listeners). Mediator is typically MANY-TO-MANY and BIDIRECTIONAL:
colleagues both send events to and receive instructions from a central
coordinator that also decides workflow sequencing between them
(`CheckoutMediator` deciding pricing runs after stock is reserved). Rule of
thumb: reach for Observer when you have "an unknown/growing number of
parties that want to know X happened"; reach for Mediator when you have "a
small, fixed set of collaborators whose CROSS-TALK is getting tangled."

**Senior:** "When would you graduate from an enum with behavior (like
`OrderStatus.canTransitionTo`) to the full State pattern? Give a concrete
threshold, not just 'when it gets complex.'"
*Ideal answer:* Two concrete triggers (both covered in this module's
`OrderStateContext` Javadoc): (1) the number of DISTINCT per-status
BEHAVIORS (not just transition legality) grows past one or two — fee
calculation, editability, allowed actions, SLAs, required approvals are
each a separate concern that starts accumulating as separate switch
statements if left in enum-land (`NaiveStatusConditionals` shows this
scattering); or (2) per-status behavior needs to be INJECTED or configured
at RUNTIME (e.g. a cancellation fee percentage that varies by merchant tier
or comes from a database) — an interface implementation can take
constructor parameters, a fixed enum constant's behavior cannot vary at
runtime. Absent either trigger, the enum is simpler and should be kept.
*Follow-up:* "Is this decision reversible/costly to change later?" → Fairly
cheap to defer — going from enum to State later is a mechanical refactor
(one class per constant, moving each `case` arm's logic into the matching
class) as long as calling code already goes through a narrow interface
(`OrderStatus.canTransitionTo`) rather than switching on the raw enum value
all over the codebase — another argument for encapsulating status logic
behind methods from day one, regardless of which implementation you start
with.

**Scenario (a strong Goldman Sachs/JPMorgan-style live-refactor prompt):**
"Here's an `OrderProcessor` that validates (stock, fraud, credit checks
nested in one method), charges (if/else on payment type string), adds
surcharges (boolean flags), and notifies — all in one class with a
hardcoded `Inventory` dependency. Refactor it, naming which pattern
addresses which part, and explain your ordering of changes if you were
doing this incrementally in a live production codebase (not a rewrite)."
*Ideal answer:* Map each smell to a fix: nested validation → Chain of
Responsibility (`StockCheckHandler` → `FraudCheckHandler` →
`CreditCheckHandler`); payment if/else → Strategy (`PaymentProcessor`
implementations) or Factory Method if the creation side also needs
decoupling; boolean-flag surcharges → Decorator
(`GiftWrapDecorator`/`ExpressShippingDecorator`); the hardcoded `Inventory`
field → Dependency Inversion (constructor injection, SOLID.md); the overall
method becoming the simplified single entry point → Facade. For
INCREMENTAL live-production refactoring (the senior-differentiating part of
this answer): extract and cover with characterization tests FIRST (capture
current behavior, bugs included, before changing anything), then refactor
one seam at a time behind the EXISTING public method signature so callers
are unaffected at each step, deferring the riskiest change (usually
untangling the dependency injection / testability seam) until the
lower-risk internal refactors are already validated in production. This is
exactly the kind of "how do you refactor safely, not just what's the target
design" question that distinguishes a senior answer from a mid-level one
that only describes the end state.
