# Module 1 — Line-by-Line Explanation

This walks through every file in [src/main/java/com/interviewprep/orders](src/main/java/com/interviewprep/orders) in the order you should read them (dependencies first). The "why" for each design choice is also in the inline code comments — this file adds narrative and connects choices across files.

## `domain/Customer.java`

```java
public record Customer(String id, String name, String email) {
```
Declares a record: this single line generates a private final field per component, a canonical constructor `Customer(String id, String name, String email)`, accessors `id()`/`name()`/`email()`, and structural `equals()`/`hashCode()`/`toString()`. No manual boilerplate needed.

```java
public Customer {
    if (id == null || id.isBlank()) { throw new IllegalArgumentException(...); }
    if (email == null || !email.contains("@")) { throw new IllegalArgumentException(...); }
}
```
This is a **compact canonical constructor** — no parameter list, because it reuses the record header's parameters implicitly. It runs *before* the implicit field assignment, so throwing here means an invalid `Customer` can never be constructed at all — validation is centralized, not repeated at every call site that builds one.

## `domain/Product.java`

Same record pattern as `Customer`. The one thing to internalize: `price` is `BigDecimal`, not `double`. The validation (`price.signum() < 0`) rejects negative prices — `signum()` returns -1/0/1 without the precision pitfalls of comparing `BigDecimal` with `<`.

## `domain/OrderLine.java`

```java
public record OrderLine(Product product, int quantity) {
```
Composition in action: this record *holds a* `Product` reference rather than extending it. Extending would mean an `OrderLine` *is a* `Product` — false, since an order line has no independent existence as a sellable item.

```java
public BigDecimal lineTotal() {
    return product.price().multiply(BigDecimal.valueOf(quantity));
}
```
Records can declare additional methods beyond the generated ones — `lineTotal()` isn't a component, it's a derived computation. `BigDecimal.valueOf(quantity)` converts the `int` to a `BigDecimal` safely (unlike `new BigDecimal(double)`, which would inherit binary floating-point imprecision — not a concern here since `quantity` is already an `int`, but the habit matters for any `double`-sourced `BigDecimal`).

## `domain/OrderStatus.java`

```java
public enum OrderStatus {
    PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED;
```
Five constants — a closed, compile-time-known set. This is what makes the exhaustiveness checking below possible; you cannot accidentally forget a status because adding one forces every exhaustive `switch` over `OrderStatus` (including the one below) to handle it or fail to compile.

```java
public boolean canTransitionTo(OrderStatus next) {
    return legalNextStates().contains(next);
}
```
Public API: asks "is this transition legal?" without mutating anything — a pure query, safe to call speculatively (e.g. to decide whether to show a "Cancel" button in a UI).

```java
private Set<OrderStatus> legalNextStates() {
    return switch (this) {
        case PENDING -> Set.of(CONFIRMED, CANCELLED);
        ...
        case DELIVERED -> Set.of();
        case CANCELLED -> Set.of();
    };
}
```
A **switch expression** (Java 14+, arrow form): each `case` yields a value directly (no `break`, no fall-through). The compiler verifies every enum constant is covered — if a sixth status were added without a matching `case`, this fails to compile rather than silently returning `null` or falling through unexpectedly, which is exactly the failure mode a `switch` *statement* with `String` or `int` would have allowed.

## `domain/InsufficientStockException.java`

```java
public class InsufficientStockException extends RuntimeException {
```
Unchecked — see the extensive Javadoc in the file and the README's Exception Handling section for the full reasoning: this is a business condition with one designated handler downstream, not something every intermediate caller should be forced to declare.

```java
super("Insufficient stock for sku '%s': requested %d, available %d".formatted(sku, requested, available));
```
`String.formatted(...)` (Java 15+) is `String.format(...)` called as an instance method — purely a readability improvement, functionally identical. The message is built once and passed to `RuntimeException`'s constructor so `getMessage()` returns something actionable in logs.

The three accessor methods (`sku()`, `requested()`, `available()`) let a catch site build a structured response (e.g. a REST error body in Module 5) instead of parsing the exception's message string — never parse an exception message to extract data; expose fields instead.

## `domain/Inventory.java`

```java
private final Map<String, Integer> stockBySku = new HashMap<>();
```
`private final` — the reference itself can't be reassigned, and critically, it's never returned to a caller (see the Javadoc's encapsulation discussion). `HashMap` chosen because there's no ordering requirement and O(1) average lookup is exactly what's needed for "how much stock does this SKU have."

```java
public void restock(String sku, int quantity) {
    requirePositive(quantity, "restock quantity");
    stockBySku.merge(sku, quantity, Integer::sum);
}
```
`Map.merge(key, value, remappingFunction)`: if `sku` isn't present, inserts `quantity`; if it is present, replaces the value with `remappingFunction.apply(oldValue, quantity)` — here, `Integer::sum`. This is a one-line replacement for the classic "get, add if present else put" pattern, and it's atomic *per call* on a `ConcurrentHashMap` (not on plain `HashMap`, which is what Module 3 fixes).

```java
public void reserve(String sku, int quantity) {
    requirePositive(quantity, "reserve quantity");
    int available = stockOf(sku);
    if (available < quantity) {
        throw new InsufficientStockException(sku, quantity, available);
    }
    stockBySku.put(sku, available - quantity);
}
```
Read-then-check-then-write. Note this is exactly the shape of a **race condition** under concurrent access (two threads can both pass the check before either writes) — flagged deliberately in the class Javadoc and left unfixed until Module 3, where the fix (and why plain `synchronized`, `ConcurrentHashMap.compute()`, and per-SKU locks each have different trade-offs) gets full treatment.

```java
public int stockOf(String sku) {
    return stockBySku.getOrDefault(sku, 0);
}
```
`getOrDefault` avoids `if (map.containsKey(sku)) {...} else {...}` or a null-check after `get()` — an unqueried SKU simply reads as zero stock, which is the correct business meaning (no special "unknown SKU" case to handle everywhere).

## `domain/Order.java`

```java
private final List<OrderLine> lines = new ArrayList<>();
private OrderStatus status = OrderStatus.PENDING;
```
`lines` is `final` (the reference never changes) but the *list itself* is mutable via `addLine` — a deliberate distinction. `status` starts `PENDING`, matching `OrderStatus`'s transition table (which only allows leaving `PENDING`, never entering it from another state).

```java
public List<OrderLine> getLines() {
    return List.copyOf(lines);
}
```
`List.copyOf` (Java 10+) returns an **immutable** snapshot. Any attempt to mutate the returned list (`.add()`, `.remove()`) throws `UnsupportedOperationException` immediately, rather than silently succeeding and corrupting the real `Order` state. This is the fix for the "wrong" example in the README (`return lines;` directly).

```java
public BigDecimal totalAmount() {
    return lines.stream()
            .map(OrderLine::lineTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
}
```
A three-stage stream pipeline: `stream()` opens it, `.map(OrderLine::lineTotal)` (a **method reference**, shorthand for `line -> line.lineTotal()`) transforms each `OrderLine` into its `BigDecimal` total, and `.reduce(identity, accumulator)` folds the stream down to a single sum starting from `BigDecimal.ZERO`.

```java
public void transitionTo(OrderStatus next) {
    if (!status.canTransitionTo(next)) {
        throw new IllegalStateException(...);
    }
    status = next;
}
```
Delegates the legality check entirely to `OrderStatus` — `Order` doesn't know or care *why* a transition is illegal, only that `OrderStatus` said so. This keeps the two concerns (what states exist / what transitions are legal, vs. how an individual order's state changes) cleanly separated.

## `service/OrderService.java`

```java
private final AtomicLong orderIdSequence = new AtomicLong(1);
```
`AtomicLong` rather than a plain `long` counter — a preview of Module 3. A plain `long orderIdSequence` incremented via `orderIdSequence++` is **not** thread-safe (that's a read-modify-write, same race shape as `Inventory.reserve`'s bug) even though nothing in Module 1's single-threaded demo would expose it. Using `AtomicLong.getAndIncrement()` here costs nothing today and is already correct if this class were used concurrently.

```java
public Order placeOrder(Customer customer, List<OrderLine> requestedLines) {
    Deque<OrderLine> reserved = new ArrayDeque<>();
    try {
        for (OrderLine line : requestedLines) {
            inventory.reserve(line.product().sku(), line.quantity());
            reserved.push(line);
        }
    } catch (InsufficientStockException e) {
        for (OrderLine line : reserved) {
            inventory.release(line.product().sku(), line.quantity());
        }
        throw e;
    }
    ...
}
```
This is the most important method in Module 1 to understand deeply, because it's a hand-rolled version of what a database transaction gives you for free. Walking through it: for each requested line, reserve its stock immediately and remember it in `reserved` (an `ArrayDeque` used as a stack via `push`). If any `reserve()` call throws, the `catch` block walks back through everything *already* reserved and releases it — undoing the partial work — before re-throwing the *original* exception (`throw e`, not a new one — preserving the stack trace and type, per the README's "never lose the cause" warning). If nothing throws, the method falls through to constructing the `Order` and attaching every line to it.

```java
public BigDecimal totalSpentByImperative(...) { /* for loop */ }
public BigDecimal totalSpentByStreams(...) { /* stream pipeline */ }
```
Two implementations of the identical computation, side by side on purpose — `Main.java` calls both and asserts they agree, so you can see the equivalence rather than take it on faith.

```java
public Map<OrderStatus, List<Order>> ordersByStatus(List<Order> orders) {
    return orders.stream().collect(Collectors.groupingBy(Order::status));
}
```
`Collectors.groupingBy(classifier)` is the streams equivalent of: create an empty `Map<OrderStatus, List<Order>>`, loop over `orders`, and for each one do `map.computeIfAbsent(order.status(), k -> new ArrayList<>()).add(order)`. One line replaces that whole loop.

## `Main.java`

A sequential script exercising every piece above: builds products/customer/inventory, places a valid order, deliberately places an order that must fail (proving `InsufficientStockException` fires *and* that stock is restored afterward — the rollback), walks a legal `OrderStatus` path to `DELIVERED` and then proves an illegal transition out of it is rejected, compares the imperative and streams total-spend calculations, and finally prints the `groupingBy` result. Every `System.out.println` is there so running it gives immediate, readable proof that each concept behaves as explained above — read the console output next to this file when you run it.
