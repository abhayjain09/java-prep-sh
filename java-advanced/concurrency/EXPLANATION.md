# Module 3 — Line-by-Line Explanation

This walks through every file in
[src/main/java/com/interviewprep/orders/concurrency](src/main/java/com/interviewprep/orders/concurrency)
grouped by theme, in the order you should read them. The "why" for each
design choice is also in the inline code comments — this file adds
narrative and connects choices across files. Domain classes (`Customer`,
`Product`, `Order`, `OrderLine`, `Inventory`, `InsufficientStockException`,
`OrderService`) are imported unchanged from `java-basics/` — see
`java-basics/EXPLANATION.md` if you need a refresher on those.

## Part 1 — The race condition and its fixes

### `ReservableInventory.java`

```java
public interface ReservableInventory {
    void restock(String sku, int quantity);
    void reserve(String sku, int quantity);
    void release(String sku, int quantity);
    int stockOf(String sku);
}
```
An interface extracted specifically because this module needs FOUR
interchangeable implementations (the original unsafe `Inventory`, plus three
fixes) exercised through identical test code (`InventoryStressTester`). This
is a concrete instance of "extract an interface once you have two-plus
implementations used polymorphically" — not a blanket "always code to an
interface" rule; Module 1's `Inventory` correctly stayed a concrete class
because it had exactly one implementation and no such need.

### `UnsafeInventoryAdapter.java`

Wraps Module 1's `Inventory` (unmodified — this module never edits
`java-basics/`) behind `ReservableInventory` via pure delegation, so the
ORIGINAL buggy class can sit in the same stress-test harness as its fixes.
No logic of its own; every method is a one-line forward to `delegate`.

### `ConcurrentInventory.java`

```java
stockBySku.compute(sku, (key, currentStock) -> {
    int available = currentStock == null ? 0 : currentStock;
    if (available < quantity) {
        throw new InsufficientStockException(sku, quantity, available);
    }
    return available - quantity;
});
```
This is the whole fix. `ConcurrentHashMap.compute(key, remappingFunction)`
runs the remapping function under an internal per-bin lock held for the
ENTIRE call — the read (`currentStock`), the check (`available < quantity`),
and the write (the returned value becomes the new mapping) all happen as one
indivisible unit as far as any other thread can observe. Throwing inside the
lambda is deliberate and correct: `compute()`'s contract guarantees that if
the remapping function throws, the map is left completely unchanged for that
key — we get "all or nothing" for a single-key operation with no manual
rollback code needed (contrast with `OrderService.placeOrder`'s
`Deque`-based manual rollback, which is still needed for multi-key/multi-line
orders, since `compute()`'s atomicity is strictly per-key).

`restock`/`release` reuse `merge(sku, quantity, Integer::sum)` — identical
code to Module 1's `Inventory`, but now genuinely atomic per-key because the
backing map is `ConcurrentHashMap` instead of `HashMap`.

### `SynchronizedInventory.java`

```java
private final Object lock = new Object();
...
synchronized (lock) {
    int available = stockBySku.getOrDefault(sku, 0);
    if (available < quantity) { throw new InsufficientStockException(...); }
    stockBySku.put(sku, available - quantity);
}
```
Notice the code INSIDE the `synchronized` block is byte-for-byte identical
to Module 1's original buggy `reserve()` — the fix isn't a different
algorithm, it's wrapping the exact same read-check-write sequence in mutual
exclusion so no other thread can interleave inside it. The lock is a
dedicated private `Object`, not `synchronized(this)` or `synchronized`
methods — see the class Javadoc for why exposing the lock object (even
implicitly, via `this`) is a real hazard: any external code holding a
reference to this instance could synchronize on it too, either introducing
surprise contention or stalling every legitimate caller.

### `StripedLockInventory.java`

```java
private final ReentrantLock[] stripes;
...
private ReentrantLock stripeFor(String sku) {
    int index = Math.floorMod(sku.hashCode(), stripes.length);
    return stripes[index];
}
```
A fixed-size array of locks (16 by default), each SKU hashed to one stripe.
`Math.floorMod` (not `%`) matters here: `String.hashCode()` can be negative,
and `%` in Java preserves the sign of its left operand — a negative result
would be an invalid array index and throw `ArrayIndexOutOfBoundsException`.
`Math.floorMod` always returns a value in `[0, stripes.length)`.

```java
lock.lock();
try {
    int available = stockBySku.getOrDefault(sku, 0);
    if (available < quantity) { throw new InsufficientStockException(...); }
    stockBySku.put(sku, available - quantity);
} finally {
    lock.unlock();
}
```
The `try/finally` around a manually-acquired `ReentrantLock` is not
optional decoration — it's the difference between "correct" and "a lock
that's held forever the first time an exception fires mid-critical-section."
`stockOf()` deliberately does NOT take a stripe lock (see its comment): a
plain `ConcurrentHashMap.get()` is already thread-safe for a point-in-time
read; only the compound check-then-decrement in `reserve()` needs the lock
held across its whole duration.

### `InventoryStressTester.java`

```java
CountDownLatch startingGate = new CountDownLatch(1);
...
executor.submit(() -> {
    startingGate.await();
    for (int i = 0; i < reservationsPerThread; i++) { ... }
});
...
startingGate.countDown(); // fire the starting gun
```
Every worker thread blocks on `startingGate.await()` immediately after being
submitted, then all are released together by one `countDown()` call — this
maximizes how much the threads' work actually overlaps in time, which
maximizes the chance of hitting the race window if one exists. Without this,
threads submitted to a pool start running at slightly different times as the
pool schedules them, understating real contention.

The nested `StressTestResult` record's `isConsistent()` — `successCount ==
initialStock && finalStock == 0` — is the single invariant every correct
implementation must satisfy: exactly as many reservations succeed as there
was stock to satisfy, and nothing is left over or driven negative.

### `RaceConditionDemo.java` / `ConcurrencyFixComparisonDemo.java`

Both are thin orchestrators over `InventoryStressTester` — the former runs
the ORIGINAL unsafe `Inventory` (via `UnsafeInventoryAdapter`) through the
identical stress test the latter runs against all three fixes, so the
"before" and "after" are a true apples-to-apples comparison, not just an
assertion. Read their extensive `System.out.println` narration alongside a
real run's output — it's written to explain what you're looking at as you
look at it, especially the "a clean run doesn't prove the bug is fixed"
caveat.

## Part 2 — Deadlock

### `Warehouse.java`

A deliberately different shape from `ReservableInventory`: its raw
accessors (`stockOfUnguarded`, `setStockUnguarded`, `restockUnguarded`) take
NO lock themselves — by design, the caller (`StockTransferService`) is
responsible for holding `lock()` first. The "Unguarded" naming is a cheap,
real defense: it makes misuse visible at the call site to any reviewer.

### `StockTransferService.java`

Three methods, same underlying operation (`doTransfer`), three different
locking strategies:

```java
public void transferUnsafe(Warehouse from, Warehouse to, String sku, int quantity, long holdDelayMillis) {
    from.lock().lock();
    try {
        sleepQuietly(holdDelayMillis);   // widen the window on purpose (demo only!)
        to.lock().lock();
        try { doTransfer(from, to, sku, quantity); }
        finally { to.lock().unlock(); }
    } finally { from.lock().unlock(); }
}
```
Locks `from` then `to` — in whatever order the CALLER passed them. Two
threads transferring in opposite directions acquire the two locks in
OPPOSITE order — the deadlock setup. `holdDelayMillis` exists purely to make
the resulting deadlock reliably observable in a short demo run; real
production code should never sleep while holding a lock.

```java
public void transferOrdered(Warehouse from, Warehouse to, String sku, int quantity) {
    Warehouse first = from.id().compareTo(to.id()) <= 0 ? from : to;
    Warehouse second = (first == from) ? to : from;
    first.lock().lock();
    try {
        second.lock().lock();
        try { doTransfer(from, to, sku, quantity); }
        finally { second.lock().unlock(); }
    } finally { first.lock().unlock(); }
}
```
The FIX: lock order is decided by comparing `Warehouse.id()` — a stable,
total ordering independent of which warehouse is logically the transfer
source or destination. `doTransfer` still moves stock `from -> to` exactly
as requested; only the ORDER OF LOCK ACQUISITION changed, not the transfer
semantics. With every caller obeying the same global order, a cycle (thread
A holding lock 1 wanting lock 2, thread B holding lock 2 wanting lock 1) is
structurally impossible.

```java
public boolean transferWithTimeout(Warehouse from, Warehouse to, String sku, int quantity, Duration overallTimeout) throws InterruptedException {
    ...
    if (fromLock.tryLock(50, TimeUnit.MILLISECONDS)) {
        try {
            if (toLock.tryLock(50, TimeUnit.MILLISECONDS)) {
                try { doTransfer(...); return true; }
                finally { toLock.unlock(); }
            }
        } finally { fromLock.unlock(); }
    }
    Thread.sleep(ThreadLocalRandom.current().nextInt(5, 25));
    ...
}
```
The alternative fix for when no natural lock order exists: bound EVERY lock
acquisition attempt with a timeout, and if the second lock can't be
acquired in time, release the first immediately (never sit holding one lock
while blocking indefinitely for another) and retry after a RANDOMIZED
backoff. The randomization matters: a fixed backoff risks both threads
retrying in perfect lockstep forever (livelock — busy, but never finishing).

### `DeadlockDemo.java`

```java
Thread t1 = new Thread(() -> transferService.transferUnsafe(east, west, SKU, 10, 300), "transfer-east-to-west");
t1.setDaemon(true);
```
Both deadlocking threads are DAEMON threads, launched directly (not via an
`ExecutorService`) — this is deliberate: a genuine deadlock never resolves
itself, and daemon threads let the JVM exit normally once the demo is done
observing them, instead of hanging forever. Plain `ReentrantLock.lock()`
does not respond to `Thread.interrupt()` (only `lockInterruptibly()` does),
so there is no way to un-stick these two threads from inside the program —
this class doesn't try.

```java
long[] deadlockedIds = threadBean.findDeadlockedThreads();
if (deadlockedIds != null) {
    ThreadInfo[] infos = threadBean.getThreadInfo(deadlockedIds, true, true);
    ...
}
```
`ThreadMXBean.findDeadlockedThreads()` is the JDK's built-in cycle detector
over threads blocked on monitors and `java.util.concurrent` lock
synchronizers — the exact same underlying mechanism `jstack -l <pid>` and
tools like VisualVM use to report "Found one Java-level deadlock." Polling
it in a loop (rather than a single check) accounts for the fact that the
deadlock takes a moment to actually form after both threads start.

`demonstrateFix()` runs the identical opposite-direction concurrent access
pattern through `transferOrdered` (and separately through
`transferWithTimeout`), using a real `ExecutorService` this time — since
neither fix can deadlock, letting the pool manage the threads and calling
`Future.get(timeout)` (itself a defensive habit: if either method somehow
still had a bug, this fails fast with `TimeoutException` instead of hanging
the whole demo) is safe.

## Part 3 — ExecutorService + CompletableFuture

### `PaymentResult.java` / `PaymentDeclinedException.java` / `OrderOutcome.java`

Small value types supporting the async pipeline: `PaymentResult` (a
successful charge), `PaymentDeclinedException` (unchecked, same rationale as
`InsufficientStockException` — see Module 1), and `OrderOutcome` (the single
normalized return type `AsyncOrderProcessor` produces on EVERY path,
success or failure, so callers never have to catch exceptions out of a
`CompletableFuture` chain themselves).

### `PaymentGateway.java`

```java
public CompletableFuture<PaymentResult> chargeAsync(Order order, Executor executor) {
    return CompletableFuture.supplyAsync(() -> charge(order), executor);
}
```
`supplyAsync(Supplier<T>, Executor)` runs `charge(order)` on the GIVEN
executor (not the common `ForkJoinPool` default, which would be the wrong
choice here — see README section 4) and returns a `CompletableFuture<T>`
immediately, without blocking the caller. `charge()` simulates network
latency with `Thread.sleep` and throws `PaymentDeclinedException` for orders
at or above a fixed threshold — a deterministic stand-in for a real
gateway's non-deterministic decline reasons, so this demo can reliably
exercise both the success and failure paths.

### `AsyncOrderProcessor.java`

```java
public CompletableFuture<OrderOutcome> processOrderAsync(Customer customer, List<OrderLine> requestedLines) {
    return CompletableFuture
            .supplyAsync(() -> reserveAndCreateOrder(customer, requestedLines), executor)
            .thenCompose(order -> paymentGateway.chargeAsync(order, executor)
                    .thenApply(payment -> confirm(order, payment))
                    .exceptionally(ex -> cancelAfterPaymentFailure(order, ex)))
            .handle((outcome, ex) -> ex == null ? outcome : failedBeforePayment(customer, ex));
}
```
Read this top to bottom as a pipeline: **stage 1** (`supplyAsync`) reserves
stock and creates the `Order` — an in-memory, fast operation, still run
asynchronously so the caller isn't blocked even briefly. **Stage 2+3**
(inside `thenCompose`) charges payment (I/O-bound, slow — `chargeAsync`
itself returns a `CompletableFuture`, which is exactly why `thenCompose`
rather than `thenApply` is needed here: `thenApply` would produce a
`CompletableFuture<CompletableFuture<PaymentResult>>`, a nested future
almost nobody wants) and, if that succeeds, confirms the order via
`thenApply`. The `.exceptionally(...)` immediately after handles ONLY a
payment failure — at that point stock IS reserved and needs releasing,
which `cancelAfterPaymentFailure` does before returning a "failed but
handled" `OrderOutcome`. **The final `.handle(...)`** is the pipeline's only
step that runs on EVERY completion, success or failure — its job is
normalizing the one remaining failure path (reservation failing before
payment was ever attempted, in which case `ex != null` here and no
`OrderOutcome` value exists yet) into the same return type.

```java
Deque<OrderLine> reserved = new ArrayDeque<>();
try {
    for (OrderLine line : requestedLines) {
        inventory.reserve(line.product().sku(), line.quantity());
        reserved.push(line);
    }
} catch (InsufficientStockException e) {
    for (OrderLine line : reserved) { inventory.release(line.product().sku(), line.quantity()); }
    throw e;
}
```
`reserveAndCreateOrder` is Module 1's `OrderService.placeOrder` reservation
loop, unchanged in shape — same manual all-or-nothing rollback via a
`Deque`-as-stack. The only real difference: `inventory` here is a
`ReservableInventory` (thread-safe per call), because this method now runs
concurrently across a thread pool, potentially for multiple orders touching
the SAME sku at once — exactly the scenario Module 1's plain `Inventory`
could not survive.

```java
private static Throwable rootCause(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && (cause instanceof CompletionException || cause instanceof ExecutionException)) {
        cause = cause.getCause();
    }
    return cause;
}
```
Exceptions thrown inside `supplyAsync`/`thenApply`/etc. are NOT propagated
as-is to downstream stages — the `CompletableFuture` machinery wraps them in
a `CompletionException` (or `ExecutionException`, if observed via a blocking
`Future.get()`). Without unwrapping, every failure message would read
"java.util.concurrent.CompletionException" with no indication of the actual
business exception underneath — `rootCause` walks the cause chain back to
the original `InsufficientStockException`/`PaymentDeclinedException`.

### `BatchOrderProcessingDemo.java`

```java
inventory.restock(laptop.sku(), 3); // 4 customers order 1 each -> the 4th fails
...
Product server = new Product("SKU-SERVER", "Rack Server", new BigDecimal("6000.00")); // >= decline threshold
...
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```
The batch is deliberately seeded so every branch of `AsyncOrderProcessor`'s
pipeline executes at least once in a single run: enough successful orders,
one that exhausts stock (exercising `failedBeforePayment`), and one priced
above the payment gateway's decline threshold (exercising
`cancelAfterPaymentFailure`, including its stock-release logic).
`CompletableFuture.allOf(...)` completes once EVERY future in the array has
completed (success or failure) — note it does NOT itself carry any of their
results; `.join()` on it is purely a synchronization point to wait past
before reading each individual (already-resolved) future's result via its
own `.join()` afterward.

## Part 4 — Virtual Threads

### `VirtualThreadsDemo.java`

```java
try (ExecutorService platformPool = Executors.newFixedThreadPool(FIXED_POOL_SIZE)) {
    platformTime = timeIoBoundTasks(platformPool, "platform pool (" + FIXED_POOL_SIZE + " threads)");
}
```
`ExecutorService` implements `AutoCloseable` (since Java 19) — its `close()`
calls `shutdown()` and awaits termination, so a plain try-with-resources
guarantees cleanup without an explicit `finally` block, a small but genuinely
useful modern idiom.

The I/O-bound task body is just `Thread.sleep(IO_TASK_SLEEP_MILLIS)` — a
stand-in for any real blocking call (network, DB, disk). The CPU-bound task
body is a tight arithmetic loop that never calls anything blocking — the
`AtomicLong sink` accumulating each task's result exists ONLY to give the
JIT a reason not to notice the loop's result is otherwise unused and
eliminate the whole computation as dead code (a real, easy-to-hit pitfall in
hand-rolled microbenchmarks — see the comment for why JMH's `Blackhole` is
the rigorous version of this trick).

Compare the two `run...Comparison` methods' printed explanations directly
against the numbers your own run produces — the CPU-bound comparison should
show roughly EQUAL times between the two executors; a large gap there would
actually indicate something else is going on (e.g. an unrelated bottleneck),
not virtual threads doing anything special.

## Part 5 — Atomics & Concurrent Collections

### `AtomicCountersDemo.java`

```java
long[] counter = {0};
runConcurrently(8, 10_000, () -> counter[0]++);
```
Reproduces the plain read-modify-write race on a raw counter — the
one-element array is the standard workaround for a lambda needing to mutate
a variable from its enclosing scope (a bare `long counter = 0;` couldn't be
reassigned inside the lambda at all — "effectively final" capture rules).
Compare directly against `AtomicLong.getAndIncrement()`'s always-correct
result immediately below it in the same file.

```java
peakInFlight.accumulateAndGet(current, Math::max);
```
`accumulateAndGet(x, accumulatorFunction)` is a generalized CAS-retry loop:
"atomically update this value to `accumulatorFunction.apply(currentValue,
x)`, retrying if another thread's update interleaved." Using `Math::max`
here gives a lock-free "keep the highest value ever seen" — a common
building block for tracking high-water-mark metrics (peak concurrent
requests, peak queue depth) without any locking.

```java
do {
    current = lowestStockSku.get();
    if (current != null && inventory.stockOf(current) <= stock) { break; }
} while (!lowestStockSku.compareAndSet(current, sku));
```
The manual CAS-retry pattern every Atomic convenience method (and
`ConcurrentHashMap.compute()`) is ultimately built from: read the current
value, decide on a new value, attempt to swap ONLY IF the reference still
holds what we read (`compareAndSet(expected, newValue)`), and loop back to
re-read and re-decide if another thread's swap beat us to it. The class
Javadoc is explicit that THIS particular demo's stock values don't actually
change mid-scan — the point is showing the CAS MECHANISM in isolation, not
claiming this specific computation needed it.

### `ConcurrentCollectionsDemo.java`

```java
attemptsBySku.computeIfAbsent(sku, key -> new LongAdder()).increment();
```
`computeIfAbsent` is atomic-per-key on `ConcurrentHashMap`, so even with 8
threads racing to be the first to touch a brand-new SKU, exactly one
`LongAdder` is ever created for it — no duplicate-creation race. `LongAdder`
(not `AtomicLong`) is the right tool for a counter under real WRITE
contention: internally it stripes the count across multiple cells so
different threads' increments mostly land on different cells instead of all
CAS-racing the same single value; `sum()` (called once at the end here to
print totals) walks and adds every cell, which is fine since reads are rare
relative to writes in this use case.

```java
int snapshotSizeDuringWrites = auditLog.size();
```
Deliberately read WHILE the four writer threads may still be appending —
this is the point of the demo: `CopyOnWriteArrayList` never throws
`ConcurrentModificationException` under concurrent mutation because every
mutation copies the entire backing array and every iterator/read operates
against a fixed snapshot from the moment it started.

```java
OrderRequest poisonPill = new OrderRequest(null, List.of());
...
while (true) {
    OrderRequest request = intake.take(); // blocks until work arrives
    if (request == poisonPill) { break; }
    ...
}
```
The consumer loop blocks on `take()` (not a polling loop) until a producer
`put()`s something — no busy-waiting. `poisonPill` is a sentinel object
compared by REFERENCE (`==`, not `.equals()`) specifically so the consumer
can tell "shut down now" apart from any real (even malformed) order request,
without a separate shared boolean flag the consumer would otherwise have to
poll on every iteration. Producers calling `intake.put(...)` on a BOUNDED
`ArrayBlockingQueue(10)` will themselves block if the queue is full —
deliberate backpressure, throttling fast producers to the consumer's actual
processing rate instead of growing the queue without limit.

Note this demo uses Module 1's PLAIN, UNSAFE `Inventory` directly (not any
fix from Part 1) — and that's correct, not an oversight: exactly ONE thread
(the single consumer) ever calls `reserve()`/`release()` here, so there is
no concurrent access to race on. This is a real, legitimate alternative to
locking worth remembering: funnel all mutation through a single thread
(sometimes called a "single-writer" or actor-style design) instead of making
the data structure itself concurrency-safe.

## `Main.java`

```java
private static void section(String title, ThrowingRunnable demo) {
    ...
    try { demo.run(); }
    catch (Exception e) { ...; e.printStackTrace(System.out); }
}
```
Each of the seven demos runs inside its own try/catch, printing a stack
trace and moving on rather than letting one demo's failure abort the whole
walkthrough. This matters specifically because two demos in this module
(`RaceConditionDemo`, `DeadlockDemo`) depend on real thread scheduling and
timing, which varies by machine — an environment-specific hiccup in one
demo shouldn't hide the other six demos' output.
