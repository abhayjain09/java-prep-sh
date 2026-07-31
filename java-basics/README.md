# Module 1 — Java 8→21 Evolution & Core Java Fundamentals

**Domain used throughout:** a small Order/Inventory system — `Customer`, `Product`, `Order`, `OrderLine`, `Inventory`. Every concept below is demonstrated against this same model so later modules (persistence, REST, security, Angular, AWS, system design) extend something you already understand rather than introducing a new toy example each time.

Companion files:
- [diagrams/domain-model.md](diagrams/domain-model.md) — class diagram of the domain used in this module and beyond
- [diagrams/java-evolution-timeline.md](diagrams/java-evolution-timeline.md) — Java 8→21 feature timeline
- [src/](src/) — the actual code
- [EXPLANATION.md](EXPLANATION.md) — line-by-line walkthrough of every file in `src/`
- [EXERCISES.md](EXERCISES.md) — hands-on exercises
- [INTERVIEW.md](INTERVIEW.md) — beginner/intermediate/senior/scenario interview questions with ideal answers

---

## 1. Java 8 → 21 Evolution

### What it is
Java shipped one release every ~3 years until Java 9, then moved to a fixed 6-month release cadence in 2017 (Java 9 onward), with LTS (Long-Term Support) releases every 2 years — 8, 11, 17, 21. Most enterprises run an LTS version in production.

### Why it matters for interviews
Interviewers use "what's new since Java 8" as a quick calibration question — it tells them whether you've kept current or you're reciting 2015-era knowledge. It's also a strong signal of whether you can *justify* a language feature (why it exists, what problem it solves) rather than just name it.

### The timeline that matters (see [diagrams/java-evolution-timeline.md](diagrams/java-evolution-timeline.md) for the full visual)

| Version | Year | LTS? | Headline features relevant to this module |
|---|---|---|---|
| 8 | 2014 | Yes | Lambdas, Streams, `Optional`, default methods on interfaces, new `java.time` |
| 9 | 2017 | No | Module system (Jigsaw), `List.of()`/`Map.of()` immutable factory methods |
| 10 | 2018 | No | Local-variable type inference (`var`) |
| 11 | 2018 | Yes | New `HttpClient`, `String` methods (`isBlank`, `strip`, `repeat`), single-file source launch |
| 14 | 2020 | No | Switch expressions (`->` form) finalized |
| 16 | 2021 | No | Records finalized, Pattern matching for `instanceof` finalized |
| 17 | 2021 | Yes | Sealed classes finalized |
| 21 | 2023 | Yes | Pattern matching for `switch` finalized, record patterns, **Virtual Threads** finalized |

**Deliberately deferred to later modules:** Virtual Threads get full treatment in Module 3 (Concurrency) — they're mentioned here only so the timeline is accurate; explaining them properly requires the threading model background that module builds first. Similarly, `java.nio.file` (introduced in Java 7, expanded since) is covered in Module 2 (File System APIs), not here.

### Problem each era solved
- **Java 8 (lambdas/streams):** Before 8, operating on collections meant verbose external iteration (`for` loops) or clunky anonymous inner classes for callbacks. Streams + lambdas let you express *what* to compute, not *how* to loop, and enabled easy parallelization (`parallelStream()`).
- **`var` (Java 10):** Reduces boilerplate for obvious right-hand-side types (`var order = new Order(...)`) without making Java dynamically typed — the compiler still infers and fixes a single static type at compile time.
- **Records (16):** Before records, an immutable data carrier (constructor + getters + `equals`/`hashCode`/`toString`) required 30-40 lines of boilerplate or a Lombok dependency. Records generate all of that from a one-line declaration.
- **Sealed classes (17):** Lets you say "these are the *only* permitted subclasses" of a type — closing the door on unexpected implementations and enabling the compiler to verify a `switch` over subtypes is exhaustive.
- **Pattern matching for `switch` (21):** Combines sealed classes + records + switch into concise, exhaustive, type-safe branching — replaces long `if (x instanceof A a) {...} else if (x instanceof B b) {...}` chains.

### When to use / when not to use
- Use `var` for local variables where the type is obvious from the right-hand side or a long generic type would hurt readability (`var lines = new ArrayList<OrderLine>()`). **Don't** use it when it hides meaning (`var result = process(x);` — what type is `result`?) or for fields/parameters/return types (not even legal there).
- Use records for immutable data carriers with no identity beyond their fields (a `Money` value object, a DTO). **Don't** use records for JPA entities (Module 5) — entities need mutable state, identity semantics, and a no-arg constructor that plays awkwardly with records' all-args canonical constructor.
- Use sealed classes when you control the full set of subtypes and want exhaustiveness checking (e.g. `OrderStatus` transitions, a `PaymentResult` that's exactly `Success | Declined | Error`). Don't use them for extensibility points meant for plugins/third parties — sealed is the opposite of open for extension.

### Trade-offs & performance implications
- Streams have a small per-element overhead (boxing for primitive streams unless you use `IntStream`/`LongStream`/`DoubleStream`, lambda/functional-interface dispatch) versus a hand-written loop. For hot paths processing millions of elements, a loop can be measurably faster — but for typical business logic (hundreds to low-thousands of items), the difference is noise next to I/O or DB latency. **Measure before optimizing away readability.**
- `parallelStream()` uses the common `ForkJoinPool` — sharing that pool with unrelated parallel work (including, unexpectedly, other libraries) can cause contention. It also has poor cache locality for small collections; it typically only pays off for large (10k+) element collections with CPU-bound per-element work.
- Records' generated `equals`/`hashCode` are field-by-field — fine for value objects, but be careful using large object graphs as record fields (deep equality cost) or mutable fields inside a record (breaks the immutability guarantee records are meant to signal).

### Enterprise examples
- A payments platform modeling `PaymentResult` as a sealed interface (`Success`, `Declined`, `Error`) so every call site is forced (via exhaustive `switch`) to handle all three — no silently-ignored case.
- Migrating DTOs from Lombok `@Value` classes to records during a Java 17 upgrade to drop a build dependency.

### Common mistakes
- Assuming `var` makes Java dynamically typed (it doesn't — type is fixed at compile time, `var x = 5; x = "hi";` doesn't compile).
- Using streams for anything with side effects or early termination logic that's clearer as a loop, just because "streams are more modern."
- Forgetting sealed classes still require `permits` (or same-file/same-module) declarations, and every permitted subtype must itself be `sealed`, `non-sealed`, or `final`.

---

## 2. OOP in the domain model

### What it is
Encapsulation, inheritance, polymorphism, and composition are demonstrated concretely in [src/main/java/com/interviewprep/orders/domain](src/main/java/com/interviewprep/orders/domain):

- **Encapsulation:** `Inventory` never exposes its backing `Map` directly — stock can only change through `reserve()`/`release()`/`restock()`, so invariants (never negative stock) are enforced in one place.
- **Composition over inheritance:** `OrderLine` *has a* `Product` and a quantity — it does not extend `Product`. An order line isn't a more-specific product; it's a product plus order-specific data (quantity, price-at-time-of-order). This is a deliberate choice over the tempting-but-wrong alternative of `OrderLine extends Product`.
- **Polymorphism without a class hierarchy:** `OrderStatus` is an enum with behavior (`canTransitionTo(OrderStatus next)`), showing that polymorphism-like behavior doesn't always require classes/interfaces — enums can carry per-constant behavior too.

### Why introduced / problem it solves
Encapsulation prevents "shotgun" bugs where stock could be decremented from ten different call sites with ten slightly different bounds-checks (or none). Composition avoids fragile, deep inheritance hierarchies that break when a subtype doesn't cleanly fit the "is-a" relationship — a classic interview trap is asking "should `OrderLine extend Product`?" to see if a candidate reaches for inheritance reflexively.

### When to use / when not to use
- Favor composition by default. Reach for inheritance only when there's a genuine is-a relationship *and* you need to substitute subtypes polymorphically (Liskov substitution) — not just to reuse fields.
- Keep mutable state behind methods (encapsulation) whenever an invariant must hold across all mutations (e.g. "stock never negative"). Public mutable fields are acceptable only for pure, invariant-free data holders (and even then, a record is usually a better fit than a mutable class with public fields).

### Trade-offs
- Composition means more forwarding/delegation code than inheritance would give you "for free" — an intentional cost in exchange for flexibility and avoiding the fragile base class problem.
- Enum-with-behavior (`OrderStatus`) is simple here because the set of statuses is fixed and small. If the state machine grows complex (many statuses, many transition rules, external configuration), a dedicated State pattern (Module 4) is a better fit.

### Enterprise examples
- Order-management systems at scale almost universally model status as a closed set with explicit legal transitions (exactly what `OrderStatus.canTransitionTo` demonstrates) — this prevents illegal transitions like `DELIVERED → PENDING` from ever compiling into business logic unchecked.

### Common mistakes
- Reaching for inheritance to "share code" between `Product` and `OrderLine` because they both have a `price` field — a classic sign composition was the right call and inheritance was used for the wrong reason.
- Exposing a mutable collection field via a plain getter (`public List<OrderLine> getLines() { return lines; }`) — callers can then mutate internal state without going through any validation. See `Order.getLines()` in the code for the defensive-copy fix.

---

## 3. Collections & Generics

### What it is
`Order` holds `List<OrderLine>`; `Inventory` is backed by `Map<String, Integer>` (product ID → quantity on hand). Generics (`List<OrderLine>` vs. a raw `List`) give compile-time type safety — the compiler rejects adding a `Product` where an `OrderLine` is expected, and callers don't need casts when reading.

### Problem it solves
Before generics (pre-Java 5), collections held `Object`, meaning every read needed a cast, and type errors surfaced at runtime (`ClassCastException`) instead of compile time. Generics move that error to compile time — a strictly better failure mode (caught by the developer, not a user in production).

### Wrong vs. correct (see `OrderService` comments for the runnable version)
```java
// WRONG — raw type, no compile-time safety, cast required, error surfaces at runtime
List lines = new ArrayList();
lines.add(new OrderLine(product, 2));
OrderLine line = (OrderLine) lines.get(0); // ClassCastException risk if something else got added

// CORRECT — generic type, compiler enforces contents, no cast needed
List<OrderLine> lines = new ArrayList<>();
lines.add(new OrderLine(product, 2));
OrderLine line = lines.get(0);
```

A second wrong/correct pair worth internalizing — mutating a list while iterating it with a for-each loop:
```java
// WRONG — throws ConcurrentModificationException
for (OrderLine line : order.getLines()) {
    if (line.quantity() == 0) order.getLines().remove(line);
}

// CORRECT — use the iterator's own remove, or removeIf
order.getLines().removeIf(line -> line.quantity() == 0);
```

### When to use which collection
- `ArrayList`: default choice for ordered, index-accessed data (order lines in the order they were added). O(1) get, O(n) insert/remove in the middle.
- `LinkedList`: rarely the right choice in modern Java — only when you need O(1) insert/remove at both ends *and* don't need random access; `ArrayDeque` usually beats it even for queue/stack use cases.
- `HashMap`: default choice for key→value lookup with no ordering requirement (product ID → stock count). O(1) average get/put.
- `LinkedHashMap`: like `HashMap` but preserves insertion order — useful for deterministic test output or simple LRU caches (via its access-order constructor, previewed here, detailed in the Caching module).
- `TreeMap`/`TreeSet`: when you need sorted iteration order (e.g. products sorted by SKU) — O(log n) operations, more expensive than hash-based structures.

### Trade-offs & performance implications
- `HashMap` get/put is O(1) average but O(n) worst case under pathological hash collisions (mitigated since Java 8, which treeifies buckets with many collisions).
- Generics use **type erasure** — generic type parameters don't exist at runtime (a `List<OrderLine>` and a `List<Product>` are the same `List` class at runtime). This is why you can't do `new T()` or `new T[]` inside a generic method, and why overloading solely on generic parameter type doesn't work.
- Defensive copies (returning `List.copyOf(lines)` instead of the live list) cost an allocation per call — fine for typical business logic, but avoid in a hot loop copying large lists repeatedly.

### Enterprise examples
- Inventory systems modeling stock as `Map<String, Integer>` (or `Map<Sku, StockLevel>` with proper types) is extremely common — the interview-relevant nuance is *never* returning that map directly from a public getter (see encapsulation above).

### Common mistakes
- Raw types (shown above) — still seen in legacy codebases pre-Java 5, an instant red flag in code review.
- Using `==` to compare boxed `Integer`s outside the `-128..127` cache range, expecting value equality (`Integer.valueOf(200) == Integer.valueOf(200)` is `false`). Always use `.equals()` for boxed type comparison, and note `Inventory`'s use of primitive `int` where possible to sidestep this entirely.
- Mutating a collection field returned by a getter, believing it's "just reading."

---

## 4. Streams, Lambdas & Functional Programming

### What it is
A **lambda** is an anonymous implementation of a functional interface (an interface with exactly one abstract method — `Runnable`, `Comparator<T>`, or the `java.util.function` family: `Function`, `Predicate`, `Supplier`, `Consumer`). A **stream** is a pipeline of operations (filter/map/reduce/collect) over a source of elements, evaluated lazily until a terminal operation runs.

### Problem it solves
Before Java 8, sorting a list with a custom comparator or filtering a collection meant either an anonymous inner class (`new Comparator<Order>() { public int compare(...) {...} }`) or an external loop with an `if` and manual accumulation into a new list. Both are verbose and separate the *intent* (what you want) from a lot of scaffolding (how to loop, how to accumulate).

### Imperative vs. Streams (see `OrderService` for the real version)
```java
// Imperative — compute total value of all orders for a customer
BigDecimal total = BigDecimal.ZERO;
for (Order order : orders) {
    if (order.customer().equals(customer)) {
        total = total.add(order.totalAmount());
    }
}

// Streams — same result, declarative
BigDecimal total = orders.stream()
    .filter(order -> order.customer().equals(customer))
    .map(Order::totalAmount)
    .reduce(BigDecimal.ZERO, BigDecimal::add);
```

### When to use / when not to use
- Use streams for transformation pipelines (filter → map → collect) over collections where the logic reads more clearly as a pipeline than a loop — grouping orders by status, finding top customers by spend, flattening order lines across orders.
- Avoid streams when: the loop body has complex control flow (multiple early returns, `break`/`continue`-like logic — `takeWhile`/`dropWhile` cover some but not all cases), when debugging step-by-step matters more than pipeline elegance (stack traces through lambda chains are noisier), or when performance profiling shows the stream overhead matters (rare, but real in tight inner loops).
- `Optional` (introduced alongside streams) should be used as a **return type** for "may not have a value" (`Optional<Customer> findById(...)`), never as a field type, method parameter, or wrapped in a collection (`List<Optional<T>>` is almost always wrong — filter out empties instead).

### Trade-offs & performance implications
- Boxing: `Stream<Integer>` boxes every `int`; for numeric-heavy pipelines use `IntStream`/`LongStream`/`DoubleStream` to stay primitive and avoid allocation overhead.
- Lambdas capturing variables from an enclosing scope must capture **effectively final** variables — this occasionally forces an extra local variable or an array/holder workaround, which is a common "why won't this compile" interview trip-up.
- Stream pipelines are lazy — nothing runs until a terminal operation (`collect`, `forEach`, `reduce`, `count`, ...) is invoked. A stream with only intermediate operations (`filter`, `map`) and no terminal op silently does nothing, which surprises people coming from other languages' eager collection APIs.

### Enterprise examples
- Reporting/aggregation code (e.g. "total order value per customer this month") is one of the most common real-world uses of `Collectors.groupingBy` combined with a downstream collector (`Collectors.summingDouble`, `Collectors.counting`).

### Common mistakes
- Reusing a stream after a terminal operation (`IllegalStateException: stream has already been operated upon or closed`) — streams are single-use.
- Side-effecting lambdas inside `map`/`filter` (e.g. mutating an external list) — makes the pipeline hard to reason about and is unsafe under `parallelStream()`.
- Calling `.get()` on an `Optional` without checking `.isPresent()`/using `.orElse`/`.orElseThrow` — reintroduces exactly the `NullPointerException`-shaped bug `Optional` exists to make explicit.

---

## 5. Exception Handling

### What it is
`InsufficientStockException` (in `domain/`) is a custom exception thrown when `Inventory.reserve()` is asked for more stock than is available. It's used to demonstrate checked vs. unchecked exceptions and where each is appropriate.

### Problem it solves
Exceptions separate error-handling code from the normal control-flow path, and — critically — they can't be silently ignored the way a C-style error return code can (nothing forces a caller to check a return value, but an unhandled checked exception won't compile, and an unhandled unchecked one crashes loudly instead of continuing with corrupt state).

### Checked vs. unchecked — and which this project uses
`InsufficientStockException` extends `RuntimeException` (unchecked) rather than `Exception` (checked). Rationale, and the trade-off it represents:
- **Checked exceptions** force every caller up the stack to either handle or declare the exception (`throws`). Good for *recoverable* conditions the caller can reasonably act on (e.g. `IOException` — retry, fall back, prompt the user). Bad when the exception ripples through many layers that have no meaningful recovery action, forcing boilerplate `throws` clauses or catch-and-wrap-and-rethrow everywhere.
- **Unchecked exceptions** (`RuntimeException` and subclasses) don't force declaration. Appropriate for *programming errors* or business-rule violations that should propagate up to a single, central handler rather than be caught at every layer (this is exactly the pattern Spring Boot's `@ExceptionHandler`/`@ControllerAdvice` uses — previewed here, detailed in Module 5).
- Insufficient stock is treated as unchecked here because in the REST API this evolves into (Module 5), the natural handling point is one global exception handler translating it into a 409 Conflict response — not something every intermediate method needs to declare or catch.

### When to use / when not to use
- Use a custom exception type when the caller needs to distinguish this failure from others programmatically (catch `InsufficientStockException` specifically to show "out of stock" vs. a generic error). Don't create a custom exception type for every possible failure — that's over-engineering; reserve it for cases with distinct handling.
- Never use exceptions for ordinary control flow (e.g. throwing to break out of a loop instead of using `break`) — exceptions are comparatively expensive (stack trace capture) and it obscures intent.

### Trade-offs & performance implications
- Constructing an exception captures a full stack trace by default (`Throwable.fillInStackTrace()`), which has real cost if exceptions are thrown at high frequency in a hot path. For truly hot-path, high-frequency "expected" conditions, consider overriding `fillInStackTrace()` to skip it (documented as an advanced technique — used sparingly, since losing stack traces hurts debuggability).
- try-with-resources (Java 7+) is the correct pattern once real I/O appears (Module 2) — it guarantees `close()` is called even on exception, and its own suppressed-exception mechanism avoids losing the original exception when `close()` itself throws.

### Enterprise examples
- A checkout flow throwing `InsufficientStockException` mid-transaction and having a single global handler translate it into a structured API error response, log it at WARN (not ERROR — it's an expected business condition, not a bug), and let the client display "only 3 left in stock."

### Common mistakes
- Catching `Exception` (or worse, `Throwable`) broadly and swallowing it silently — hides real bugs and makes production issues nearly undebuggable.
- Using checked exceptions for conditions that are really just bugs (e.g. a checked `InvalidArgumentException` for a null parameter — that should be an unchecked `IllegalArgumentException` thrown immediately via a precondition check).
- Wrapping an exception without preserving the cause (`throw new RuntimeException(e.getMessage())` instead of `throw new RuntimeException(e)`) — destroys the original stack trace, making root-causing production incidents much harder.

---

## Next module

Module 2 — File System APIs (`java.io`, `java.nio`, `Path`/`Files`, `WatchService`, ZIP/CSV/JSON/XML, serialization, large-file processing) — will extend this same domain (e.g. importing a CSV of orders, exporting an inventory snapshot). Not started until you confirm Module 1 is solid.
