# Module 4 — Walkthrough of Every Wrong/Correct Pair

This file connects the wrong (naive) and correct (pattern-applied)
implementations pattern by pattern. Each entry is deliberately concise given
the breadth of this module (23 patterns) — the full reasoning lives in the
Javadoc of the files themselves; this is the map, not the territory. Read
[SOLID.md](SOLID.md) and [GRASP.md](GRASP.md) first if you haven't — most
entries below reference back to a specific principle.

---

## Creational

### Singleton — `patterns/creational/singleton/`
`NaiveInventoryRegistry` has three layered bugs: a check-then-act race on
lazy init (same shape as `Inventory.reserve()`'s documented race in
java-basics), hidden global mutable state, and untestability from static
field leakage between tests. `InventoryRegistry` fixes the race with the
initialization-on-demand holder idiom — but its Javadoc's real payload is
the second half: even a THREAD-SAFE hand-rolled Singleton is usually the
wrong call once a DI container exists (Module 5 replaces it with a
singleton-scoped `@Bean`), because the deeper problems (hidden dependency,
poor testability) are architectural, not about locking.

### Factory Method — `patterns/creational/factorymethod/`
`NaivePaymentProcessorCreation` if/else-chains on a raw `PaymentMethod`
string, duplicated at every creation call site (Open/Closed violation, see
SOLID.md). Two corrected shapes are shown: `PaymentProcessorCreator` (the
CLASSIC GoF Factory Method — an abstract Creator whose factory method is
overridden per subclass) and `PaymentProcessorFactory` (a "simple factory"
using an `EnumMap<PaymentMethod, Supplier<PaymentProcessor>>` — what teams
actually reach for day to day). The Javadoc explicitly contrasts the two,
since conflating them is a common interview mix-up.

### Abstract Factory — `patterns/creational/abstractfactory/`
`NaiveDocumentCreation` picks an `Invoice` and a `Receipt` independently via
two separate if/else chains — nothing stops a US invoice from pairing with
an EU receipt, a real compliance-shaped bug.
`OrderDocumentFactory`/`UsOrderDocumentFactory`/`EuOrderDocumentFactory`
bundle creation of the whole FAMILY behind one factory, making a mismatched
pair structurally impossible — a caller only ever holds one factory
reference per region.

### Builder — `patterns/creational/builder/`
`NaiveOrderRequest` demonstrates telescoping constructor overloads —
call-site ambiguity between adjacent booleans (`giftWrap`/`expressShipping`
transposed compiles fine, behaves wrong) and combinatorial growth as more
optional fields are added. `OrderRequestBuilder` replaces it with a fluent
chain (`.withGiftWrap().withExpressShipping()`), validated once in
`build()`.

### Prototype — `patterns/creational/prototype/`
`NaiveOrderCopy` shows two real mistakes: a manual field-by-field copier
maintained OUTSIDE the class being copied (silently drops new fields
forever), and a shallow-copy trap (sharing the same backing list reference
between "original" and "copy"). `OrderTemplate`'s own copy constructor puts
copy logic INSIDE the class and does `new ArrayList<>(other.lines)` (a real
new list) rather than aliasing — also explains why `Object.clone()` is
avoided (Effective Java's well-known critique) in favor of a copy
constructor.

---

## Structural

### Adapter — `patterns/structural/adapter/`
`ThirdPartyPaymentGateway` simulates a vendor SDK with an incompatible shape
(cents as `long`, int status codes). `NaiveDirectGatewayUsage` calls it
directly from checkout code, duplicating the cents-conversion and
status-code interpretation at every call site. `PaymentGatewayAdapter`
implements OUR `PaymentProcessor` interface and isolates the translation in
one class — checkout code never sees a vendor status code.

### Bridge — `patterns/structural/bridge/`
`NaiveNotificationClassExplosion` models "notification kind" × "delivery
channel" as ONE inheritance hierarchy, producing one class per combination
(2×2 = 4 classes shown; a third channel needs 2 more). `OrderNotification`/
`UrgentOrderNotification` (abstraction) HOLDING a `NotificationSender`
(implementor: `EmailNotificationSender`/`SmsNotificationSender`) lets both
axes vary independently — a new channel is ONE new implementor class, not
one per existing kind.

### Composite — `patterns/structural/composite/`
`NaiveBundlePricing` keeps standalone products and bundles as separate,
unrelated collections with duplicated summing logic, and can't even
represent a bundle-of-bundles. `OrderComponent` (implemented by both
`ProductLeaf` and `ProductBundle`) lets `ProductBundle.price()` recurse
uniformly with zero `instanceof` checks — nesting bundles inside bundles
works automatically. (This hierarchy is intentionally reused by Visitor,
below — see that pattern's note on GoF patterns composing together.)

### Decorator — `patterns/structural/decorator/`
`NaiveOrderPriceCalculator` uses boolean flags (`giftWrap`,
`expressShipping`) with a combinatorial if/else that grows per COMBINATION,
not per flag, and hardcodes surcharge ORDER. `BaseOrderPricer` wrapped by
`GiftWrapDecorator`/`ExpressShippingDecorator` (both extending
`OrderPricerDecorator`) makes each surcharge one small class; composing and
reordering them is a caller-side constructor-nesting decision.

### Facade — `patterns/structural/facade/`
`NaiveCheckoutClient` makes every caller re-implement the
reserve-with-rollback dance (copy-pasted from `OrderService.placeOrder` in
java-basics) plus manual sequencing of pricing and notification —
inconsistent sequencing across callers is a real risk. `CheckoutFacade`
centralizes the sequencing and rollback in ONE method; the underlying
subsystems (`Inventory`, `PricingService`, `NotificationService`) remain
independently accessible for callers with more specialized needs.

### Flyweight — `patterns/structural/flyweight/`
`NaiveOrderLineCreation` builds a brand-new `Product` per order line even
when the SAME sku repeats — at scale (millions of lines, thousands of
SKUs), this wastes memory proportional to line count instead of catalog
size. `ProductFlyweightFactory` caches one shared `Product` per sku (via
`ConcurrentHashMap.computeIfAbsent`, safe under concurrent import jobs),
relying on `Product` being an immutable record so sharing is safe.

### Proxy — `patterns/structural/proxy/`
`NaiveUncontrolledAccess` trusts every caller to remember a role check
before calling `restock()` — one forgotten check (shown explicitly in
`restockFromBatchJob`) is a silent security hole. `SecuredInventoryProxy`
implements the same `InventoryOperations` interface as the real subject and
enforces the check centrally — no caller can reach the real subject without
passing through it. The Javadoc also surveys virtual/remote/caching proxy
variants for interview breadth.

---

## Behavioral

### Strategy — `patterns/behavioral/strategy/`
`NaiveDiscountCalculator` if/else-chains on a raw discount-type string, with
magic numbers buried inside branches. `PricingContext` holds a
`DiscountStrategy` (implementations: `NoDiscountStrategy`,
`PercentageDiscountStrategy`, `FlatAmountDiscountStrategy`) and delegates —
swapping the algorithm, even at runtime via `setStrategy`, needs no code
edit.

### Observer — `patterns/behavioral/observer/`
`NaiveOrderStatusChange` hardcodes direct calls to
email/SMS/audit-log listeners inside the status-changing method itself —
adding a fourth listener means editing business-critical transition code.
`OrderStatusPublisher` lets listeners (`EmailNotificationListener`,
`SmsNotificationListener`, `AuditLogListener`) subscribe independently; the
publisher notifies all of them without knowing what any of them do. Noted
as the conceptual ancestor of Spring's `ApplicationEventPublisher`.

### Command — `patterns/behavioral/command/`
`NaiveOrderController` calls inventory/order operations as plain methods —
there's no way to undo a cancellation or log/queue a placement generically.
`OrderCommand` (implementations: `PlaceOrderCommand`, `CancelOrderCommand`)
encapsulates a request as an object with `execute()`/`undo()`;
`OrderCommandInvoker` runs commands and maintains a history stack for
generic undo, with zero knowledge of what any given command does.

### State — `patterns/behavioral/state/`
`NaiveStatusConditionals` shows what happens once MULTIPLE per-status rules
(cancellation fee, editability) pile up as separate switch statements over
the plain `OrderStatus` enum — each status's story gets scattered across
methods instead of living together. `OrderState` (implementations:
`PendingState` … `CancelledState`) puts every rule for one status in one
class. The Javadoc gives the explicit graduation rule this module was asked
for: stay with java-basics' enum while you have one simple lookup-table
concern (transition legality); graduate to full State once several
genuinely different BEHAVIORS pile up or need runtime configuration.

### Template Method — `patterns/behavioral/templatemethod/`
`NaiveDuplicatedOrderProcessors` shows two processor classes independently
re-typing the SAME four-step skeleton (validate → reserve → charge →
notify), differing only in two steps — a policy change to step ordering
means editing both, and they can drift. `OrderProcessorTemplate` makes
`process()` `final` (the fixed skeleton) with `chargeShipping`/
`notifyCustomer` as overridable hooks (`StandardOrderProcessor`,
`ExpressOrderProcessor`) — shared steps are written once.

### Chain of Responsibility — `patterns/behavioral/chainofresponsibility/`
`NaiveOrderValidator` runs stock/fraud/credit checks nested inside one
monolithic method — can't reorder, skip, or reuse individual checks (e.g.
an admin override needing every check except credit). `OrderValidationHandler`
(implementations: `StockCheckHandler`, `FraudCheckHandler`,
`CreditCheckHandler`) each do one check and forward via `setNext` — chains
are assembled once, at wiring time, and different chains can coexist for
different flows.

### Iterator — `patterns/behavioral/iterator/`
`NaiveManualIndexIteration` reaches into a catalog's exposed internal `List`
and separate stock `Map`, re-implementing "in stock only" filtering at every
call site with manual index loops. `InventoryCatalog implements Iterable<Product>`
hides both internal structures behind a custom `InStockProductIterator` —
the filtering rule lives in exactly one place.

### Mediator — `patterns/behavioral/mediator/`
`NaiveDirectColleagueCommunication` wires checkout "colleagues"
(inventory-check, pricing, notification) with direct references to each
other — coupling grows roughly with the square of colleague count, and the
workflow is scattered across whichever colleague triggers the next step.
`CheckoutMediator` (implemented by `CheckoutMediatorImpl`) centralizes the
workflow; colleagues (`InventoryColleague`, `PricingColleague`,
`NotificationColleague`) only know the mediator interface.

### Memento — `patterns/behavioral/memento/`
`NaiveManualBackup` has the CALLER copy an `OrderEditor`'s fields into loose
local variables to "back them up" — a new field on `OrderEditor` silently
breaks this backup with no compiler warning (the same forgotten-field risk
as Prototype's naive copier). `OrderMemento` (created/unpacked only by
`OrderEditor` itself, via package-private access) captures an opaque
snapshot; `OrderHistoryCaretaker` manages a history stack without ever
looking inside a memento.

### Visitor — `patterns/behavioral/visitor/`
`NaiveInstanceofOperations` re-derives the same leaf-vs-bundle
`instanceof`/recursion shape for every new operation (tax, shipping
weight), and demonstrates a real bug: `calculateShippingWeight` forgets to
recurse into bundles entirely, silently understating weight.
`OrderComponentVisitor<R>` (implemented by `TaxCalculationVisitor`,
`ShippingWeightVisitor`) is called via double dispatch
(`component.accept(visitor)`) — the compiler FORCES every visitor to
implement both `visit()` overloads, making the naive bug structurally
impossible. Deliberately reuses Composite's `OrderComponent` hierarchy to
show how GoF patterns combine in real designs.

### Interpreter — `patterns/behavioral/interpreter/`
`NaiveNestedConditionals` hardcodes each new discount-eligibility RULE
COMBINATION as its own Java method, re-deriving the same sub-conditions
(e.g. "spent > $100") repeatedly with no reuse. `DiscountRuleExpression`
(terminals `SpendOverExpression`, `CustomerEmailDomainExpression`;
combinators `AndExpression`, `OrExpression`) builds rules as composable
objects — new combinations are assembled from existing pieces, not new
Java conditionals.

---

## Cross-cutting themes worth internalizing

1. **The "type string with if/else" anti-pattern appears in Strategy,
   Factory Method, and State** — recognizing this ONE recurring shape
   ("branching on a type code to select behavior/creation") and knowing
   THREE different named fixes depending on what's varying (algorithm →
   Strategy, object creation → Factory Method, an object's own lifecycle →
   State) is one of the highest-leverage interview skills this module
   teaches.
2. **The "forgotten field in manual copy/backup code" bug appears in both
   Prototype and Memento** — putting copy/snapshot logic INSIDE the class
   being copied (rather than beside it, in caller code) is the shared fix.
3. **Indirection (GRASP) is the shared mechanism behind Adapter, Facade,
   Mediator, Observer, and Proxy** — all five insert an intermediate object
   specifically to avoid two other things depending on each other directly;
   what differs is WHY (incompatible interface → Adapter; too many
   subsystem calls → Facade; tangled peer-to-peer calls → Mediator; unknown
   number of interested parties → Observer; access control/laziness →
   Proxy).
4. **Composite + Visitor is a real, common pairing** — a stable tree
   structure with a growing set of operations over it is exactly when you
   reach for both together, as shown by Visitor importing Composite's
   hierarchy directly in this module.
