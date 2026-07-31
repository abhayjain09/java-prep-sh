# Module 1 — Interview Questions

Organized by topic, then by level (beginner → intermediate → senior → scenario). Each includes an ideal answer outline and likely follow-ups. These are the kind of questions asked in the first technical round at companies like S&P Global, JPMorgan, and Goldman Sachs (Java fundamentals bar-raise before system design even starts) as well as broader tech (Amazon, Microsoft, Google) loops that include a language-fundamentals segment.

---

## Java Evolution (8→21)

**Beginner:** "What's new in Java 8?"
*Ideal answer:* Lambdas, the Streams API, `Optional`, default/static methods on interfaces, and the new `java.time` package replacing the mutable, thread-unsafe `Date`/`Calendar`. Mention *why* each mattered (lambdas enabled Streams; default methods let interfaces evolve without breaking implementers).
*Follow-up:* "Why couldn't interfaces add methods before Java 8 without breaking every implementation?" → Because adding an abstract method to an interface previously forced every implementing class to add it too, immediately breaking binary/source compatibility for every consumer of that interface — this is exactly why default methods exist.

**Intermediate:** "What are records, and when would you *not* use one?"
*Ideal answer:* Records (Java 16) are compiler-generated immutable data carriers — see `Customer`/`Product`/`OrderLine` in this module. Don't use them for JPA entities (need mutability + a no-arg constructor), for objects with identity independent of field values (an `Order`, which has a lifecycle), or when you need inheritance (records are implicitly `final` and can't extend another class, only implement interfaces).
*Follow-up:* "Can a record have additional methods beyond its accessors?" → Yes — see `OrderLine.lineTotal()`, a derived, non-component method.

**Senior:** "How would you evaluate whether a legacy codebase is safe to upgrade from Java 8 to Java 17?"
*Ideal answer:* Check for use of internal `sun.*`/`com.sun.*` APIs (removed/restricted by the module system since 9), reflection into JDK internals (blocked more aggressively each release), deprecated APIs actually removed by 17 (e.g. `SecurityManager` deprecated for removal), third-party library compatibility (especially reflection-heavy frameworks like older Spring/Hibernate versions), and whether the build tooling (Maven/Gradle plugins) supports the target bytecode level. Recommend an incremental path (8→11→17) with automated test coverage as the safety net, not a single big-bang jump.
*Follow-up:* "What would make you *not* recommend upgrading immediately?" → Insufficient test coverage to catch behavioral regressions, or a critical unmaintained dependency that hard-pins an old Java version.

**Scenario:** "Your team wants to model a `PaymentResult` that can be exactly `Success`, `Declined`, or `Error` — nothing else, ever. How would you design that in modern Java, and why is it better than three booleans or a status enum with a message field?"
*Ideal answer:* A `sealed interface PaymentResult permits Success, Declined, Error`, each a record implementing it. Combined with a `switch` pattern-matching over the sealed type, the compiler enforces every call site handles all three cases — a fourth subtype can't be added elsewhere without updating `permits`, and forgetting to handle one in a `switch` is a compile error, not a runtime bug discovered in production.

---

## OOP (Encapsulation, Composition vs. Inheritance, Polymorphism)

**Beginner:** "What's the difference between composition and inheritance? Give an example from this codebase."
*Ideal answer:* Inheritance models "is-a" (a `Manager` is an `Employee`); composition models "has-a" (an `Order` has `OrderLine`s; an `OrderLine` has a `Product`). `OrderLine` doesn't extend `Product` because an order line isn't a more specific product — it's a product referenced with extra context.

**Intermediate:** "Why is `Inventory`'s backing map private, and what would go wrong if it had a public getter returning the live map?"
*Ideal answer:* `Inventory.reserve()`/`release()`/`restock()` enforce the invariant "stock never negative." A public getter returning the live `Map` would let any caller do `inventory.getStock().put(sku, -5)` bypassing every check — the invariant could be violated from anywhere in the codebase, making bugs nearly impossible to trace to one cause.
*Follow-up:* "What's the fix if you *do* need to expose the current stock levels for display?" → Return an unmodifiable view (`Collections.unmodifiableMap(...)`) or, better, a defensive copy / a purpose-built read-only DTO — never the live mutable structure.

**Senior:** "When is inheritance the *right* choice, given how strongly this module argues for composition?"
*Ideal answer:* When there's a genuine is-a relationship *and* you need runtime polymorphic substitution — callers hold a reference to the supertype and correctly don't need to know the concrete subtype (Liskov substitution holds cleanly). A `PaymentMethod` interface with `CreditCardPayment`/`BankTransferPayment` implementations processed identically by calling code is a legitimate case; forcing shared fields onto unrelated concepts just to avoid retyping them is not.

**Scenario:** "A junior engineer proposes `OrderLine extends Product` to 'avoid retyping the price field.' How do you respond in code review?"
*Ideal answer:* Point out the Liskov substitution violation directly: anywhere code expects a `Product` (e.g. a product catalog listing), substituting an `OrderLine` would be nonsensical — it has an order-specific quantity that a catalog entry shouldn't have. Recommend composition (`OrderLine` holds a `Product`) and note that the "avoid retyping" motivation is a sign to reach for composition/delegation, not inheritance, when the goal is code reuse rather than a true type relationship.

---

## Collections & Generics

**Beginner:** "What does type erasure mean, and what's one practical consequence of it?"
*Ideal answer:* Generic type parameters exist only at compile time; at runtime, `List<OrderLine>` and `List<Product>` are both just `List`. Practical consequence: you can't do `new T()` inside a generic method, can't create a generic array (`new T[]`), and can't overload methods that differ only in generic type parameter (`process(List<String>)` and `process(List<Integer>)` collide after erasure).

**Intermediate:** "Why does `HashMap` give O(1) average lookup, and when does that degrade?"
*Ideal answer:* Keys are hashed to determine a bucket; a good hash spreads keys evenly so each bucket holds ~O(1) entries. It degrades toward O(n) under many hash collisions (especially with a poor or malicious `hashCode()`) — mitigated since Java 8, where buckets with enough collisions convert from a linked list to a balanced tree (O(log n) worst case instead of O(n)).
*Follow-up:* "What's a `HashMap`-based denial-of-service vector, and how was it mitigated?" → Attacker-supplied keys engineered to all hash into the same bucket (hash-flooding), degrading lookups to O(n); Java's treeification of large buckets bounds the worst case to O(log n).

**Senior:** "Walk through the trade-offs between `HashMap`, `TreeMap`, and `LinkedHashMap` for the `Inventory` use case, and justify the actual choice made in this module."
*Ideal answer:* `HashMap` (chosen): O(1) average get/put, no ordering guarantee — correct since stock lookups by SKU have no inherent order requirement. `TreeMap`: O(log n), sorted iteration — would only matter if you needed to iterate SKUs in sorted order (e.g. a sorted stock report), at a real performance cost for the common case (single-SKU lookup). `LinkedHashMap`: preserves insertion order at a small memory/performance overhead over `HashMap` — useful if deterministic iteration order for logging/testing mattered more than raw lookup performance.

**Scenario:** "You inherit a codebase full of raw `List`/`Map` types (pre-generics style). What's your migration strategy, and what risks do you flag to your team before starting?"
*Ideal answer:* Migrate incrementally, module by module, adding generic parameters and letting the compiler surface every now-illegal mixed-type usage as a compile error — that list of compile errors *is* the list of latent bugs the raw types were hiding. Risk to flag: some of those "errors" might reveal that a collection genuinely holds heterogeneous types today (a design smell), which needs an actual refactor (e.g. a sealed type or separate collections), not just a mechanical generic-parameter addition.

---

## Streams, Lambdas & Functional Programming

**Beginner:** "What's a functional interface, and how does it relate to lambdas?"
*Ideal answer:* An interface with exactly one abstract method (e.g. `Comparator<T>`, `Function<T,R>`, `Predicate<T>`). A lambda expression is syntactic sugar for an anonymous implementation of a functional interface's single method — `order -> order.totalAmount()` is shorthand for a full anonymous class implementing, say, `Function<Order, BigDecimal>`.

**Intermediate:** "Why is a stream 'lazy,' and what's a bug that laziness can cause?"
*Ideal answer:* Intermediate operations (`filter`, `map`) build up a pipeline description but don't execute until a terminal operation (`collect`, `forEach`, `reduce`, ...) is called. Bug: writing `orders.stream().filter(o -> isStale(o))` with no terminal operation compiles fine and silently does nothing — easy to miss in code review since there's no error, just missing side effects.

**Senior:** "When would you deliberately avoid Streams in favor of a plain loop, even in new code?"
*Ideal answer:* When the logic needs early exit with complex conditions beyond what `takeWhile`/`anyMatch`/`findFirst` cleanly express; when debugging step-through matters more than pipeline elegance (stack traces through lambdas are noisier, and setting a breakpoint mid-pipeline is harder than in a loop body); when profiling shows real overhead in a hot path (boxing in non-primitive streams, or `parallelStream()` contention on the shared `ForkJoinPool`); or when the operation has necessary side effects that make a "declarative" pipeline actively misleading to read.

**Scenario:** "A teammate calls `.parallelStream()` on every collection operation 'for performance.' How do you evaluate whether that's actually helping?"
*Ideal answer:* Benchmark, don't assume — `parallelStream()` uses the shared common `ForkJoinPool`, so contention with other parallel work (including unrelated library calls) can make it *slower*, not faster. It only tends to help for large collections (roughly 10k+ elements, contextual) with genuinely CPU-bound per-element work and no shared mutable state in the lambda (mutating shared state from a parallel stream introduces race conditions identical in shape to `Inventory.reserve()`'s documented bug). For I/O-bound or small-collection work, sequential is very likely both simpler and faster.

---

## Exception Handling

**Beginner:** "What's the difference between a checked and an unchecked exception?"
*Ideal answer:* Checked exceptions (subclasses of `Exception`, not `RuntimeException`) must be either caught or declared with `throws` — the compiler enforces it. Unchecked exceptions (`RuntimeException` and subclasses) require no such declaration and can propagate freely.

**Intermediate:** "Why does `InsufficientStockException` in this module extend `RuntimeException` instead of `Exception`?"
*Ideal answer:* It's a business-rule violation with one natural handling point downstream (eventually a single Spring `@ControllerAdvice`, in Module 5). Making it checked would force every method between `Inventory.reserve()` and that single handler to either declare or catch-and-rethrow it — pure boilerplate at layers with no meaningful recovery action of their own.
*Follow-up:* "Give a case where a checked exception *would* be the right call." → `IOException`-style conditions where the immediate caller can meaningfully decide to retry, fall back to a default, or prompt the user — i.e., where forcing acknowledgment at the call site has real value, not just further layers.

**Senior:** "What's the performance cost of exceptions, and when does it actually matter?"
*Ideal answer:* Constructing a `Throwable` captures a full stack trace via `fillInStackTrace()` by default — real cost if thrown at high frequency on a hot path (e.g. thousands of times per second for an "expected" condition). Matters in tight validation loops or parser-style code using exceptions for control flow (an anti-pattern in itself). Rarely matters for genuinely exceptional, low-frequency business errors like `InsufficientStockException`. An advanced mitigation for hot-path "expected" exceptions is overriding `fillInStackTrace()` to skip stack capture — at the cost of losing debuggability, so used sparingly and deliberately.

**Scenario:** "You find `catch (Exception e) { }` (empty body) in production code that's been silently swallowing errors for months. How do you fix it, and how do you prevent recurrence?"
*Ideal answer:* Immediately: identify what the catch was likely guarding against, replace the empty body with at minimum a log statement at an appropriate level (and re-throw or handle explicitly if there's a real recovery path) — never leave a catch block that discards information silently. Prevention: static analysis rules (e.g. a linter or Checkstyle/PMD rule flagging empty catch blocks) in CI, plus code review culture that treats broad `catch (Exception e)` as a flag requiring explicit justification in a comment.
