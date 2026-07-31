# SOLID Principles

**Domain used throughout:** the same Order/Inventory system from
[java-basics](../java-basics) — `Customer`, `Product`, `Order`, `OrderLine`,
`Inventory`, `OrderStatus`, `OrderService`. Every principle below is shown
as a violation FIRST (grounded in this domain), then corrected — the same
teaching shape as java-basics/README.md.

Companion files: [GRASP.md](GRASP.md) · [gof/](src/main/java/com/interviewprep/orders/patterns) ·
[README.md](README.md) (module index) · [EXPLANATION.md](EXPLANATION.md) ·
[EXERCISES.md](EXERCISES.md) · [INTERVIEW.md](INTERVIEW.md)

---

## Why SOLID matters for interviews

SOLID isn't five independent rules to memorize — it's five different lenses
on the SAME underlying goal: code that tolerates change without a rewrite.
Senior interviewers rarely ask "define the Single Responsibility Principle"
in isolation; they ask you to review a chunk of code (often a deliberately
bloated class) and identify WHICH principle it violates and HOW you'd fix
it — exactly the format used below. This is a near-universal segment of
senior Java loops at S&P Global, JPMorgan, Goldman Sachs, and the broader
FAANG-style loop (Amazon, Google, Microsoft) alike, because it's a fast,
cheap proxy for "has this person actually maintained production code, or
only greenfielded small projects."

---

## S — Single Responsibility Principle (SRP)

### What it is
A class should have exactly one reason to change — one axis of
responsibility, owned by one actor/stakeholder in the business.

### Violation: a bloated `OrderManager`

```java
// WRONG — one class doing validation, pricing, persistence, AND notification.
// Four unrelated reasons to change this class: a validation rule changes,
// a pricing formula changes, the database schema changes, or the email
// template changes — and all four are tangled in one file.
public class OrderManager {

    public Order createOrder(Customer customer, List<OrderLine> lines) {
        // Responsibility 1: validation
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Order must have at least one line");
        }
        for (OrderLine line : lines) {
            if (line.quantity() <= 0) {
                throw new IllegalArgumentException("Quantity must be positive");
            }
        }

        // Responsibility 2: pricing
        BigDecimal total = BigDecimal.ZERO;
        for (OrderLine line : lines) {
            total = total.add(line.lineTotal());
        }
        BigDecimal tax = total.multiply(new BigDecimal("0.08"));
        BigDecimal grandTotal = total.add(tax);

        // Responsibility 3: persistence (simulated)
        System.out.println("INSERT INTO orders (customer_id, total) VALUES ('"
                + customer.id() + "', '" + grandTotal + "')");

        // Responsibility 4: notification
        System.out.println("Sending email to " + customer.email()
                + ": your order total is " + grandTotal);

        Order order = new Order("ORD-1", customer);
        lines.forEach(order::addLine);
        return order;
    }
}
```

**Why this is a problem:** a change to the tax formula (finance team) risks
breaking the persistence code (DBA team) risks breaking the email template
(marketing team) — because they all live in one class with one set of unit
tests to keep green. Testing "does validation reject an empty order" now
also exercises pricing, a simulated SQL string, and a println, none of
which the test cares about.

### Corrected: split into focused collaborators

```java
// CORRECT — each class has exactly one reason to change.
public class OrderValidator {
    public void validate(List<OrderLine> lines) {
        if (lines.isEmpty()) throw new IllegalArgumentException("Order must have at least one line");
        lines.forEach(line -> { if (line.quantity() <= 0) throw new IllegalArgumentException("Quantity must be positive"); });
    }
}

public class TaxCalculator {
    private static final BigDecimal RATE = new BigDecimal("0.08");
    public BigDecimal withTax(BigDecimal subtotal) {
        return subtotal.add(subtotal.multiply(RATE));
    }
}

public class OrderRepository {
    public void save(Order order) { /* real persistence, e.g. Spring Data JPA in Module 5 */ }
}

public class OrderNotifier {
    public void notifyCreated(Order order, BigDecimal total) { /* real email/SMS send */ }
}

// OrderService (see java-basics) becomes the thin coordinator, delegating
// to each collaborator — this is Facade-flavored coordination (see
// gof/structural/facade) but the KEY point here is each collaborator now
// has ONE job and ONE reason to change.
public class OrderCreationService {
    private final OrderValidator validator;
    private final TaxCalculator taxCalculator;
    private final OrderRepository repository;
    private final OrderNotifier notifier;
    // constructor omitted for brevity

    public Order createOrder(Customer customer, List<OrderLine> lines) {
        validator.validate(lines);
        Order order = new Order("ORD-1", customer);
        lines.forEach(order::addLine);
        BigDecimal total = taxCalculator.withTax(order.totalAmount());
        repository.save(order);
        notifier.notifyCreated(order, total);
        return order;
    }
}
```

**Trade-off:** more classes, more files, more indirection to trace a single
request end to end. This cost is real and is why SRP can be over-applied —
splitting a genuinely cohesive 20-line class into five one-method classes
"because SRP" is itself a mistake (see GRASP.md's High Cohesion principle
for the balancing force). The rule of thumb: split along axes that actually
change independently and are owned by different concerns/teams, not along
every possible line.

### Interview angle
"What's wrong with this `OrderManager`?" is functionally identical to "walk
me through refactoring a God class" — expect a live-coding or whiteboard
version of the split above.

---

## O — Open/Closed Principle (OCP)

### What it is
Software entities should be open for extension but closed for modification
— adding new behavior shouldn't require editing existing, already-shipped,
already-tested code.

### Violation: if/else on a type string

```java
// WRONG — adding a new discount type means editing this method.
public BigDecimal applyDiscount(BigDecimal amount, String discountType) {
    if (discountType.equals("PERCENTAGE")) {
        return amount.subtract(amount.multiply(new BigDecimal("0.10")));
    } else if (discountType.equals("FLAT")) {
        return amount.subtract(new BigDecimal("5.00"));
    }
    // every new discount type is a new "else if" branch here
    throw new IllegalArgumentException("Unknown discount type");
}
```

This is the EXACT shape covered in full, with a complete wrong/correct pair
and runnable classes, in
[gof/behavioral/strategy](src/main/java/com/interviewprep/orders/patterns/behavioral/strategy)
— `NaiveDiscountCalculator` (wrong) vs. `DiscountStrategy` + `PricingContext`
(correct). OCP is, informally, "the reason Strategy/Factory Method/Decorator
exist" — most of the Gang of Four's structural and behavioral patterns are
specific, named techniques for achieving OCP in a particular shape of
problem.

### Interview angle
"Which GoF patterns most directly serve OCP?" — Strategy, Decorator,
Factory Method/Abstract Factory, Observer, Template Method (the fixed
skeleton is closed; hook methods are the extension point), Chain of
Responsibility. Being able to name several, and explain the MECHANISM
(polymorphism substituting for conditionals) rather than just the list, is
the senior-level bar.

---

## L — Liskov Substitution Principle (LSP)

### What it is
If `S` is a subtype of `T`, objects of type `T` should be replaceable with
objects of type `S` without altering the correctness of the program — a
subclass must honor the CONTRACT (behavior, invariants, pre/postconditions)
of its supertype, not just its method signatures.

### Violation (revisited from Module 1): `OrderLine extends Product`

java-basics/README.md and INTERVIEW.md already cover this in depth as an
OOP/composition question — worth re-stating here explicitly through the LSP
lens, since it's the same underlying issue viewed from a different named
principle (a very common interview follow-up: "you said composition over
inheritance earlier — which SOLID principle formalizes why?").

```java
// WRONG — tempting, and wrong: "avoid retyping the price field."
public class OrderLine extends Product {
    private final int quantity;
    // OrderLine now claims to BE a Product wherever a Product is expected.
}
```

**Why this violates LSP specifically:** anywhere code expects a `Product`
(e.g. a product catalog page rendering `List<Product>`, or a search index
keyed by SKU), substituting an `OrderLine` would be nonsensical — it carries
an order-specific `quantity` that has no meaning in a catalog context, and a
catalog `Product` has no meaningful "quantity ordered." The is-a relationship
is FALSE even though the code would compile. LSP violations are frequently
exactly this: code that TYPE-CHECKS but is behaviorally/semantically wrong
the moment a supertype reference is substituted.

### Corrected
```java
// CORRECT — composition: OrderLine HAS a Product, doesn't extend one.
public record OrderLine(Product product, int quantity) { /* ... */ }
```
See `java-basics/src/main/java/com/interviewprep/orders/domain/OrderLine.java`
for the actual, in-use version with full Javadoc reasoning.

### A second classic LSP violation: overriding to narrow a precondition or weaken a postcondition
```java
// WRONG — a subclass that throws on a case the superclass contract allows.
public class Inventory {
    public void restock(String sku, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("must be positive");
        // ...
    }
}

public class ReadOnlyInventory extends Inventory {
    @Override
    public void restock(String sku, int quantity) {
        // Silently violates the LSP contract: callers holding an
        // `Inventory` reference reasonably expect restock() to work (per
        // the supertype's documented behavior) — this subtype breaks that
        // expectation for ALL valid inputs, not just invalid ones.
        throw new UnsupportedOperationException("read-only");
    }
}
```
**Correct fix:** don't model "read-only" as a subtype of something whose
contract promises mutation. Use a smaller interface instead (this is also
the Interface Segregation Principle, below) — e.g. a `StockReader` interface
with only `stockOf()`, which `Inventory` implements alongside a separate
mutation-capable interface.

### Interview angle
"Give an example of an LSP violation that still compiles." — the two
examples above (a semantically-wrong is-a relationship; a subclass that
throws on cases the supertype contract allows) are exactly the kind of
answer that demonstrates real understanding versus a memorized definition.

---

## I — Interface Segregation Principle (ISP)

### What it is
Clients shouldn't be forced to depend on methods they don't use — prefer
several small, role-specific interfaces over one large, general-purpose one.

### Violation: a fat `InventoryOperations` interface

```java
// WRONG — a reporting/read-only dashboard is FORCED to implement (or
// depend on a class implementing) restock/reserve/release, none of which
// it ever calls, just to get access to stockOf().
public interface InventoryOperations {
    void restock(String sku, int quantity);
    void reserve(String sku, int quantity);
    void release(String sku, int quantity);
    int stockOf(String sku);
    void generateReorderReport();       // reporting-only concern
    void auditStockDiscrepancies();     // auditing-only concern
}
```

A read-only reporting service that only needs `stockOf()` is now coupled to
FIVE unrelated methods — a change to `restock()`'s signature forces a
recompile of the reporting service even though it never calls `restock()`.
Worse, anything implementing this interface (e.g. a test double) must stub
out all six methods, even the five it will never exercise.

### Corrected: split by role

```java
// CORRECT — small, role-specific interfaces.
public interface StockReader {
    int stockOf(String sku);
}

public interface StockWriter {
    void restock(String sku, int quantity);
    void reserve(String sku, int quantity);
    void release(String sku, int quantity);
}

public interface InventoryReportGenerator {
    void generateReorderReport();
    void auditStockDiscrepancies();
}

// A concrete Inventory can implement all three; callers depend on only the
// slice they actually need.
public class ReportingDashboard {
    private final StockReader stockReader; // depends on ONE method's worth of surface, not six
}
```
This is directly related to the `InventoryOperations` interface used in
[gof/structural/proxy](src/main/java/com/interviewprep/orders/patterns/structural/proxy)
— notice that interface is ALREADY kept small (four cohesive methods, no
reporting concerns bolted on) for exactly this reason.

### Interview angle
"How is ISP different from SRP?" — SRP is about a CLASS having one reason to
change; ISP is about an INTERFACE not forcing unrelated CLIENTS to depend on
methods they don't use. A class can satisfy SRP while still exposing a fat
interface that violates ISP for its callers — they're related but distinct
axes, and conflating them is a common tell of surface-level knowledge.

---

## D — Dependency Inversion Principle (DIP)

### What it is
High-level modules should not depend on low-level modules — both should
depend on abstractions. Abstractions should not depend on details; details
should depend on abstractions.

### Violation: `OrderService` constructing its own concrete dependency

```java
// WRONG — OrderService (a high-level policy: "how orders get placed")
// directly constructs and depends on a CONCRETE low-level detail
// (a specific Inventory implementation).
public class OrderService {
    private final Inventory inventory = new Inventory(); // hardcoded concrete dependency

    public Order placeOrder(Customer customer, List<OrderLine> lines) {
        // ... uses this.inventory directly ...
    }
}
```
**Why this is a problem:** `OrderService` cannot be unit-tested without a
real `Inventory` (no seam to inject a test double), and swapping in a
different backing implementation (e.g. a Redis-backed distributed inventory
for a multi-warehouse system) means editing `OrderService` itself, even
though placing an order conceptually has nothing to do with HOW stock is
tracked.

### Corrected (this is what java-basics already does)
```java
// CORRECT — OrderService depends on the abstraction (here, Inventory's own
// public API, injected rather than constructed) — see the real file.
public class OrderService {
    private final Inventory inventory;
    public OrderService(Inventory inventory) { // constructor injection
        this.inventory = inventory;
    }
}
```
See `java-basics/src/main/java/com/interviewprep/orders/service/OrderService.java`
— it already takes `Inventory` as a constructor parameter rather than
constructing one internally, which is DIP in practice even before any DI
framework is introduced. Module 5 (Spring) automates the WIRING of this
(a container constructs `Inventory` and hands it to `OrderService`
automatically) but the PRINCIPLE — depend on an injected abstraction, not a
self-constructed concrete detail — is exactly the same with or without a
framework.

This is also the deepest reason [Singleton is usually the wrong
answer](src/main/java/com/interviewprep/orders/patterns/creational/singleton/InventoryRegistry.java)
in DI-based code: a hand-rolled Singleton is a form of a class reaching out
and grabbing a concrete global dependency itself, instead of having an
abstraction handed to it — the opposite of dependency inversion.

### Interview angle
"What's the difference between Dependency Injection and Dependency
Inversion?" — DIP is the PRINCIPLE (depend on abstractions). Dependency
Injection is one common TECHNIQUE for achieving it (a framework or the
caller supplies the concrete implementation from outside, e.g. via
constructor parameters). You can follow DIP without a DI framework (as
`OrderService` already does); a DI framework just automates the wiring.

---

## Summary table

| Principle | One-line test | Domain example |
|---|---|---|
| SRP | "How many reasons could this class change?" | `OrderManager` → validator + pricer + repository + notifier |
| OCP | "Does adding a new X require editing existing code?" | discount type if/else → `DiscountStrategy` |
| LSP | "Can I substitute the subtype without surprises?" | `OrderLine extends Product` |
| ISP | "Does this client depend on methods it never calls?" | fat `InventoryOperations` → `StockReader`/`StockWriter` |
| DIP | "Does the high-level class construct its own low-level detail?" | `new Inventory()` inline → constructor-injected `Inventory` |

Next: [GRASP.md](GRASP.md) — a complementary, more granular set of
responsibility-assignment principles that explain WHERE to put a given
piece of behavior in the first place (SOLID mostly assumes the
classes/interfaces already exist and asks whether their shape is healthy).
