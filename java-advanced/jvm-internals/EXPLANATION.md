# Module 11 — Line-by-Line Explanation

This walks through every file in
[src/main/java/com/interviewprep/orders/jvm](src/main/java/com/interviewprep/orders/jvm)
in the order you should read them. Unlike Module 1, none of these files
prove a theory correct by their console output alone — GC/JIT/profiler
behavior is JVM-version and hardware dependent. Where a file's behavior
*is* fully deterministic (classloading, exception types), that's called
out explicitly; where it isn't, the file's comments say so and explain
what you'd need (a real JVM, JFR, JMH) to actually confirm the claim.

## How to compile and run these files yourself

This module has no Maven/Gradle setup (matching `java-basics`'s plain
`javac` approach) and depends on `java-basics`'s domain/service classes by
import. Compile both source trees together:

```bash
cd "java-prep"   # repo root
mkdir -p out
javac -d out \
  java-basics/src/main/java/com/interviewprep/orders/domain/*.java \
  java-basics/src/main/java/com/interviewprep/orders/service/*.java \
  java-advanced/jvm-internals/src/main/java/com/interviewprep/orders/jvm/*.java

java -cp out com.interviewprep.orders.jvm.MemoryRegionsDemo
java -cp out com.interviewprep.orders.jvm.AllocationPatternsDemo
java -cp out com.interviewprep.orders.jvm.EscapeAnalysisDemo
java -cp out com.interviewprep.orders.jvm.ClassLoaderHierarchyDemo
java -cp out com.interviewprep.orders.jvm.NaiveMicrobenchmarkPitfalls
java -cp out com.interviewprep.orders.jvm.LockContentionUnderLoadDemo
```

`OrderServiceJmhBenchmark.java` is deliberately excluded from the list
above — see its own section below and README section 6 for why it won't
compile without a JMH-enabled Maven module.

To actually observe GC/JIT/allocation behavior for real (not possible in
this sandbox), add flags like:

```bash
java -Xlog:gc*:file=gc.log:time,uptime,level,tags -cp out com.interviewprep.orders.jvm.AllocationPatternsDemo
java -Xlog:class+init=info -cp out com.interviewprep.orders.jvm.ClassLoaderHierarchyDemo
```

## `MemoryRegionsDemo.java`

Walks through building the same small catalog/order/inventory setup as
`java-basics/Main.java`, with a comment above every statement identifying
which memory region (stack, heap, metaspace, off-heap) owns the value it
creates.

```java
int productCount = 3;
```
Pure stack storage — a primitive local variable, zero heap involvement,
reclaimed the instant this frame pops.

```java
Product laptop = new Product("SKU-LAPTOP", "Laptop", new BigDecimal("1200.00"));
```
The **reference** `laptop` lives on the stack; the **`Product` object**
it points to is allocated in the heap's young generation (Eden), via the
calling thread's TLAB (Thread-Local Allocation Buffer — a chunk of Eden
pre-reserved per thread so ordinary allocation needs no synchronization,
just a pointer bump). This reference-vs-referent distinction is called out
explicitly because it's the most common source of confusion when people
say "the object is on the stack."

```java
Class<?> orderClass = order.getClass();
```
Doesn't allocate a new `Order` — returns the single, JVM-wide `Class<Order>`
metadata object. That metadata (bytecode, field layout, constant pool)
lives in **metaspace**, shared by every `Order` instance that will ever
exist in this JVM run. The comment block here explicitly connects this to
why creating a million `Order`s doesn't grow metaspace — only *loading new
classes* does.

```java
ByteBuffer bulkImportBuffer = ByteBuffer.allocateDirect(64 * 1024);
```
The `ByteBuffer` **object** (a small wrapper containing a native pointer)
is on the heap; the 64KB of actual byte storage it wraps is **off-heap**,
allocated as native memory outside the JVM heap entirely — tied back to
Module 2's `java.nio` channel-based bulk order import/export, where this
avoids an extra copy through a heap `byte[]` on I/O syscalls.

## `AllocationPatternsDemo.java`

```java
private static long wastefulHighAllocationVersion(Product product, Customer customer, int volume) {
    ...
    List<OrderLine> throwawayCopy = new ArrayList<>(order.getLines());
    ...
    String throwawayAudit = "processed order " + order.id() + " with " + throwawayCopy.size() + " lines";
```
Deliberately allocates more than the domain model requires: `getLines()`
already returns an immutable `List.copyOf(...)` snapshot (see
`java-basics/Order.java`), so wrapping that *again* in a fresh
`ArrayList` and immediately discarding it triples up on list allocation
for zero benefit — exactly the shape of accidental per-request waste that
shows up as elevated minor-GC frequency under real load (README section
2), discoverable via JFR's allocation events (README section 5) but
invisible to a functional test, since the behavior is identical either
way.

```java
private static long leanerLowerAllocationVersion(Product product, Customer customer, int volume) {
    ...
    lineCount += order.getLines().size();
}
```
Same business result, allocating only what `Order`'s own encapsulation
design (Module 1) requires — one `List.copyOf()` call per `getLines()`
invocation, nothing extra layered on top.

```java
// ILLUSTRATIVE NOTE (not a captured measurement ...)
```
The comment block in `main()` is explicit that the printed millisecond
numbers from this demo are a single, cold, un-warmed-up run — exactly the
"naive benchmark" anti-pattern section 6 and `NaiveMicrobenchmarkPitfalls`
describe — included only to make the demo runnable and show *some* output,
never to be quoted as a real measurement.

## `EscapeAnalysisDemo.java`

```java
public static BigDecimal sumLineTotalsNonEscaping(Product product, int[] quantities) {
    BigDecimal total = BigDecimal.ZERO;
    for (int qty : quantities) {
        OrderLine line = new OrderLine(product, qty); // does NOT escape this iteration
        total = total.add(line.lineTotal());
    }
    return total;
}
```
`line` is created, used exactly once (`.lineTotal()`), and then
unreachable — never stored in a field, never added to a collection, never
returned. This is the shape of object C2's escape analysis *can*, in
principle, prove never escapes the method, making it a scalar-replacement
candidate once the method is hot (README section 4). The Javadoc is
explicit that this is a hypothesis about JIT behavior, not something the
source code alone guarantees or this sandbox can verify.

```java
public static List<OrderLine> buildEscapingLines(Product product, int[] quantities) {
    List<OrderLine> lines = new ArrayList<>();
    for (int qty : quantities) {
        OrderLine line = new OrderLine(product, qty);
        lines.add(line); // escapes: reachable from 'lines', which escapes the method via return
    }
    return lines;
}
```
The direct contrast: every `line` here is stored into a list that is
*returned*, so by definition it's reachable after the method exits — no
escape analysis outcome could ever make scalar replacement legal for this
version, no matter how hot it gets. Pairing the two methods side by side
is the point: same object construction, different *reachability*, and
reachability is the entire criterion escape analysis is checking.

## `ClassLoaderHierarchyDemo.java`

```java
static class PoisonedAtInit {
    static final int VALUE = computeOrThrow();
    private static int computeOrThrow() {
        throw new RuntimeException("simulated static-init failure (e.g. a bad config value)");
    }
}
```
A nested class whose static initializer always throws. This is **not**
a fabricated example — it's a real, deterministic JLS-specified behavior
(JLS §12.4.2): the first active use of a class triggers `<clinit>`; if
`<clinit>` throws anything other than an `Error`, the JVM wraps it in
`ExceptionInInitializerError` **and permanently marks the class
erroneous** — it will never attempt `<clinit>` again.

```java
try {
    int firstTouch = PoisonedAtInit.VALUE;
    ...
} catch (ExceptionInInitializerError e) {
    System.out.println("First touch -> ExceptionInInitializerError, cause: " + e.getCause());
}
```
The first reference to `PoisonedAtInit.VALUE` triggers `<clinit>`, which
throws, wrapped as `ExceptionInInitializerError`.

```java
try {
    int secondTouch = PoisonedAtInit.VALUE;
    ...
} catch (NoClassDefFoundError e) {
    System.out.println("Second touch -> NoClassDefFoundError as expected: " + e.getMessage());
}
```
The *second* reference doesn't re-run `<clinit>` (the JVM remembers it
already failed) — it throws `NoClassDefFoundError` instead, and critically,
the **original `RuntimeException` is gone** from this exception's chain.
This is precisely why `NoClassDefFoundError` in a production log is often
frustratingly disconnected from its real root cause: that root cause was
only ever logged once, at the *first* touch, possibly during a completely
different request.

```java
Class.forName("com.interviewprep.orders.domain.ThisClassDoesNotExist");
```
Contrast case: this class was never compiled, never existed anywhere on
the classpath. Every classloader consulted during the delegation walk
(README section 3 / `diagrams/classloader-hierarchy.md`) genuinely fails
to find it, producing `ClassNotFoundException` — the "never existed"
failure mode, as opposed to `NoClassDefFoundError`'s "existed, then a
later use failed" failure mode.

## `NaiveMicrobenchmarkPitfalls.java`

```java
private static BigDecimal checksum = BigDecimal.ZERO;
...
for (int pass = 1; pass <= passes; pass++) {
    long start = System.currentTimeMillis();
    for (int i = 0; i < callsPerPass; i++) {
        BigDecimal result = orderService.totalSpentByStreams(customer, orders);
        checksum = checksum.add(result);
    }
    long elapsed = System.currentTimeMillis() - start;
    System.out.println("  pass " + pass + ": " + elapsed + " ms for " + callsPerPass + " calls");
}
```
The intentionally-flawed pattern this whole file exists to critique,
instrumented to at least *show* its own flaws rather than hide them:
running 5 separate timed passes (instead of one) lets you visually see the
warm-up curve (README section 4) that a single-pass naive benchmark would
hide entirely, and accumulating results into the `checksum` field is a
manual (weaker-than-JMH) defense against dead code elimination — the
Javadoc is explicit that this is a *partial* mitigation, not a substitute
for JMH's `Blackhole`.

```java
System.out.println("=== ILLUSTRATIVE EXAMPLE OUTPUT ONLY (NOT a real captured run ...
```
Every specific millisecond number printed after this line is clearly
labeled as illustrative/hypothetical — this sandbox cannot execute the
class, so no real numbers are fabricated and presented as genuine.

## `OrderServiceJmhBenchmark.java`

The entire file body is a single large block comment; the only real code
is an empty placeholder class. This is intentional and explained at length
in the comment itself: real `@Benchmark`-annotated code requires the
`org.openjdk.jmh:jmh-core` / `jmh-generator-annprocess` Maven dependencies,
which don't exist anywhere in this repo (no Maven module exists until
Module 5) and can't be fetched in this sandbox. The commented-out code is
still written to be **accurate** — correct annotations
(`@State(Scope.Benchmark)`, `@Setup`, `@BenchmarkMode(Mode.AverageTime)`,
`@Warmup`/`@Measurement`/`@Fork`), correct reasoning for each choice (see
README section 6) — so it can be copy-pasted directly into a real JMH
Maven module later, not rewritten from scratch.

## `LockContentionUnderLoadDemo.java`

```java
private static final Object PLACE_ORDER_LOCK = new Object();
...
synchronized (PLACE_ORDER_LOCK) {
    orderService.placeOrder(customer, List.of(new OrderLine(product, 1)));
}
```
Reproduces the most common real-world "fix" for `Inventory`'s documented
race condition (`java-basics/Inventory.java`'s Javadoc: `reserve()` is a
non-atomic check-then-act) — wrapping the entire `placeOrder` call in one
coarse lock. It's correct (no more lost updates) but serializes *every*
order placement across *every* thread, regardless of SKU, which is exactly
the shape of contention a JFR recording's "Lock Instances" view (README
section 5) is built to surface.

```java
CountDownLatch startGate = new CountDownLatch(1);
...
startGate.countDown(); // release all threads simultaneously
```
All worker threads block on `startGate` until released together, so the
measured window is genuinely all-threads-contending, not staggered
one-at-a-time — maximizing the contention this demo exists to illustrate.

```java
System.out.println("ILLUSTRATIVE NOTE: the elapsed time above is real ...
```
Unlike the GC-log and JMH sections above, this class prints a genuinely
real, in-process wall-clock measurement when *you* compile and run it on
your own machine — nothing here is a fabricated number. The comment in the
code is explicit, though, that a single un-warmed-up run with no fork
isolation is still not a valid throughput *benchmark* in the JMH sense;
it's a demonstration of the contention pattern, not a performance claim.
