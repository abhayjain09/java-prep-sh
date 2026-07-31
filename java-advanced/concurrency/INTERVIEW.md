# Module 3 — Interview Questions

Organized by topic, then by level (beginner → intermediate → senior →
scenario). Each includes an ideal answer outline and likely follow-ups.
Concurrency is where Java interviews at S&P Global, JPMorgan, and Goldman
Sachs routinely separate "has written Java" from "can be trusted with
production concurrent code" — financial systems are full of genuinely
concurrent, correctness-critical state (balances, positions, order books),
so expect this module's material to come up in-depth, often via a live
whiteboard "spot the bug" or "fix this deadlock" exercise. Amazon, Microsoft,
and Google loops weight it slightly differently (more Executors/async/
Virtual Threads for scalability, less "spot the deadlock" bank-account
puzzle) but the fundamentals below are universal.

---

## Race Conditions

**Beginner:** "What is a race condition? Give an example."
*Ideal answer:* A bug where program correctness depends on the relative
timing/interleaving of multiple threads accessing shared mutable state.
Example: `Inventory.reserve()` (Module 1) reads stock, checks it, then
writes back a decremented value as three separate steps — two threads can
both read the same stock level before either writes back, both pass the
"enough stock?" check, and both decrement, overselling one unit twice.
*Follow-up:* "Why doesn't this show up every time you run the code?" →
Because it depends on precise thread scheduling — the window between read
and write is tiny, so most interleavings don't hit it; this non-determinism
is exactly why race conditions pass casual testing and appear in production.

**Intermediate:** "Does simply switching `HashMap` to `ConcurrentHashMap`
fix `Inventory.reserve()`? Why or why not?"
*Ideal answer:* No. `ConcurrentHashMap` guarantees each INDIVIDUAL
`get`/`put` call is thread-safe and won't corrupt the map's internal
structure, but it does nothing to make a SEQUENCE of two calls (read, then
write) atomic together. The race is in the gap BETWEEN calls, not inside any
single call. The actual fix (`ConcurrentInventory` in this module) uses
`compute()`, which performs the read-check-write as one atomic operation per
key.
*Follow-up:* "What WOULD swapping to `ConcurrentHashMap` alone fix?" → Only
the (real, but different) hazard of the map's internal structure getting
corrupted under concurrent `put`/`resize` calls on a plain `HashMap` — which
can manifest as infinite loops or lost entries on pre-Java-8 `HashMap`, and
is a distinct bug from the business-logic race.

**Senior:** "How would you find a suspected race condition in a large,
unfamiliar codebase, given that it doesn't reproduce reliably?"
*Ideal answer:* Start from symptoms (intermittent wrong values, occasional
`ConcurrentModificationException`, data that's "sometimes" inconsistent
under load but never under light traffic) and grep for shared mutable state
(static fields, singleton fields, fields on request-scoped-but-actually-
shared objects) touched from more than one thread without synchronization.
Write a targeted stress test (many threads, many iterations, a "starting
gate" latch to maximize overlap — exactly `InventoryStressTester`'s
approach) to make the suspected bug reproduce ON DEMAND rather than relying
on production timing. Static analysis (SpotBugs/IntelliJ inspections) can
flag some patterns without running anything. For genuinely stuck cases,
thread-dump the process under load (`jstack`) at the moment of a bad
observation.
*Follow-up:* "Why not just add `synchronized` everywhere defensively?" →
Blanket synchronization can mask the true problem (if some but not all
access paths are synchronized, the bug persists but rarely), tank
throughput, and in the worst case introduce a NEW deadlock risk if it
creates multiple locks that end up needing to be held together.

**Scenario:** "A flash-sale feature oversold a limited-quantity item during
a traffic spike, but load testing in staging never caught it. Walk through
your investigation and fix."
*Ideal answer:* Confirm the shape of the bug matches this module's pattern
(check-then-act on shared stock state) rather than, say, a distributed-
systems issue (multiple app instances each with their own in-memory stock
count — a DIFFERENT bug entirely, needing a shared/DB-backed counter or
distributed lock, not just a JVM-local fix). If it's JVM-local: reproduce
with a stress test using enough concurrent threads to approximate the real
traffic spike's concurrency level (staging's load test likely had lower
CONCURRENCY, not just lower total volume, than the spike). Fix with
`ConcurrentHashMap.compute()` (simplest, if the invariant is per-item) or a
lock (if it spans multiple items/tables). Add the stress test itself to CI
so this exact bug class can't silently regress.

---

## Fixes & Lock Granularity

**Beginner:** "What does `ConcurrentHashMap.compute()` guarantee that plain
`get()` followed by `put()` doesn't?"
*Ideal answer:* `compute(key, remappingFunction)` performs the read, the
remapping function's logic, and the write as ONE atomic operation per key —
no other thread can observe or mutate that key while it runs. `get()` then
`put()` as two separate calls has a gap between them where another thread
can act.

**Intermediate:** "Compare a single `synchronized` lock over a whole
`Inventory` versus a `ReentrantLock` per SKU. What do you gain and lose?"
*Ideal answer:* Single lock (`SynchronizedInventory`): trivially easy to
reason about (only one thread ever touches state at a time), but serializes
UNRELATED operations — two threads reserving different SKUs block each
other for no real reason, capping throughput under multi-SKU contention.
Per-SKU/striped locks (`StripedLockInventory`): different SKUs run in
parallel, raising throughput under real contention, at the cost of more
subtle reasoning and, critically, introducing deadlock risk the moment an
operation needs more than one lock at once (see the Deadlocks section).
*Follow-up:* "Why does this module use a FIXED-size array of striped locks
instead of one `ReentrantLock` per distinct SKU in a
`ConcurrentHashMap<String, ReentrantLock>`?" → The per-SKU-forever approach
leaks memory in a long-running service — a lock object is created and never
evicted for every distinct SKU ever seen. A fixed-size stripe array bounds
memory regardless of how many SKUs flow through over the application's
lifetime, at the cost of occasional "false" contention between unrelated
SKUs that happen to hash to the same stripe.

**Senior:** "When would you choose `synchronized`/`ReentrantLock` over
`ConcurrentHashMap.compute()`, given `compute()` seems like the more
'modern' answer?"
*Ideal answer:* `compute()`'s atomicity boundary is strictly PER KEY — it
cannot make an invariant spanning MULTIPLE keys (or multiple different data
structures entirely) atomic. The moment you need "reserve stock for SKU-A
AND SKU-B together, all-or-nothing" or "move stock from warehouse X to
warehouse Y" (crossing two separate maps/objects), you need an explicit lock
(or locks) that can be held across the whole multi-step operation —
`compute()` has no equivalent. Also relevant: `ReentrantLock` offers
`tryLock(timeout)` and `lockInterruptibly()`, capabilities `compute()`
(and `synchronized`) don't expose, needed for the deadlock-avoidance-via-
timeout fix.
*Follow-up:* "What's the throughput cost of choosing a coarse single lock
'to be safe' when you didn't actually need multi-key atomicity?" → Every
unrelated operation serializes behind the one lock; under real concurrent
load across many keys, this can become THE system bottleneck regardless of
how many CPU cores or how well-sized the thread pool is — a common root
cause when a system "can't scale past X req/s no matter how much hardware
is added."

**Scenario:** "Your team's `synchronized`-guarded shared cache has become a
measured bottleneck under load. A teammate proposes replacing `synchronized`
with `ReentrantLock` everywhere as a quick fix. Do you agree?"
*Ideal answer:* No — swapping the lock TYPE while keeping the same
granularity (still one lock for everything) buys almost nothing;
`ReentrantLock` and `synchronized` have comparable performance for the
uncontended AND the coarsely-contended case, the bottleneck is granularity,
not lock implementation. The actual fix is reducing what one lock protects
(striping, per-key locking, or a genuinely concurrent structure like
`ConcurrentHashMap` for the parts of the cache that fit its atomic
compound-operation model) — profile first to confirm where the real
contention is before choosing a fix.

---

## Deadlocks

**Beginner:** "What is a deadlock? What are the four necessary conditions
for one to occur (Coffman conditions)?"
*Ideal answer:* Two or more threads each hold a resource another thread in
the set needs, forming a cycle with no way out. The four Coffman conditions,
ALL of which must hold: mutual exclusion (resources can't be shared), hold
and wait (a thread holds one resource while waiting for another), no
preemption (a resource can't be forcibly taken from a thread holding it),
and circular wait (a cycle of threads each waiting on the next). Breaking
ANY ONE of the four prevents deadlock — this module's fixes both break
circular wait specifically (consistent ordering removes the possibility of a
cycle; timeout-based `tryLock` breaks "no preemption" in effect, by giving
up voluntarily instead of waiting forever).

**Intermediate:** "Walk through exactly how `StockTransferService.
transferUnsafe` can deadlock, using the East/West warehouse example."
*Ideal answer:* Thread 1 calls `transferUnsafe(east, west, ...)`, locking
`east` first. Thread 2 concurrently calls `transferUnsafe(west, east, ...)`,
locking `west` first. Now thread 1 tries to lock `west` (held by thread 2)
and blocks; thread 2 tries to lock `east` (held by thread 1) and blocks.
Neither can ever proceed — a two-thread circular wait. The root cause is
that `from`/`to` argument order determines lock-acquisition order, and the
two calls pass warehouses in opposite roles.
*Follow-up:* "How would you detect this had actually happened in a running
production JVM, without being able to attach a debugger?" → `jstack -l <pid>`
(or an equivalent thread dump mechanism) explicitly reports "Found one
Java-level deadlock" with each thread's stack and which lock it's waiting on
versus holding — the same detection `ThreadMXBean.findDeadlockedThreads()`
does programmatically, as `DeadlockDemo` shows. This is a standard first
step when a service appears to be "hung" under load with CPU usage near
zero (a strong signal threads are blocked, not busy).

**Senior:** "Compare consistent lock ordering versus `tryLock`-with-timeout
as deadlock fixes. When would you reach for each?"
*Ideal answer:* Consistent ordering (sort by a stable key — an ID,
`compareTo`, or as a last resort `System.identityHashCode`) makes deadlock
STRUCTURALLY IMPOSSIBLE with zero runtime overhead once implemented, but
requires every current AND FUTURE call site that needs multiple locks to
respect the same order — a discipline/documentation burden across the whole
codebase, easy for someone unfamiliar with the convention to violate later.
`tryLock`-with-timeout works even when no natural ordering exists (e.g.
resources from unrelated subsystems), degrades gracefully (times out and
retries instead of hanging) rather than preventing deadlock outright, but
costs real CPU/latency on retries under contention and needs a sensible
give-up/backoff policy to avoid livelock.
*Follow-up:* "What's livelock, and how does this module's
`transferWithTimeout` avoid it?" → Two (or more) threads are both actively
"doing work" (retrying) but neither ever completes, because their retries
keep colliding — e.g. two threads that both fail to get a second lock,
release, and immediately retry in lockstep forever. `transferWithTimeout`
avoids this with a RANDOMIZED backoff between retries, making sustained
lockstep collision vanishingly unlikely.

**Scenario:** "You inherit a codebase where `tryLock`-with-timeout was
added everywhere 'to prevent deadlocks' after a production incident, but
lock ordering was never fixed. Six months later, a different pair of
methods deadlocks in a new way. What do you tell your team?"
*Ideal answer:* Timeouts mask the SYMPTOM (the program eventually gives up
instead of hanging forever) without fixing the underlying design flaw
(inconsistent lock acquisition order across the codebase) — and can make
failures HARDER to diagnose, since a timeout-and-give-up looks like a
generic failure/retry rather than an obvious hang a thread dump would
immediately reveal. Recommend: audit every code path that acquires more
than one lock, establish and DOCUMENT one global ordering convention (by
resource ID or another stable key) repo-wide, and add tests/stress tests
(and ideally a lint rule or code-review checklist item) that specifically
flag multi-lock acquisition that doesn't follow the convention, rather than
continuing to add more timeout band-aids reactively after each new incident.

---

## Executors & CompletableFuture

**Beginner:** "What problem does `ExecutorService` solve that raw
`new Thread()` doesn't?"
*Ideal answer:* Thread reuse (creating an OS thread per task is expensive;
a pool reuses a bounded set of worker threads across many submitted tasks)
and bounded concurrency (nothing stops unbounded `new Thread()` usage from
creating far more threads than the system can handle; a pool caps it).

**Intermediate:** "Explain the difference between `thenApply` and
`thenCompose` on a `CompletableFuture`, with an example of when using the
wrong one causes a problem."
*Ideal answer:* `thenApply(Function<T,R>)` transforms a completed value
with a SYNCHRONOUS function. `thenCompose(Function<T, CompletionStage<R>>)`
chains a step whose OWN result is itself asynchronous (already a
`CompletableFuture`). Using `thenApply` where the next step returns a
`CompletableFuture<X>` produces a `CompletableFuture<CompletableFuture<X>>`
— a nested future that isn't usable the normal way and forces an awkward
extra unwrap; `thenCompose` "flattens" it automatically. `AsyncOrderProcessor`
uses `thenCompose` specifically because `paymentGateway.chargeAsync(order)`
returns its own `CompletableFuture<PaymentResult>`.
*Follow-up:* "What does `exceptionally` NOT give you that `handle` does?" →
`exceptionally(Function<Throwable,T>)` only runs on failure and only sees
the exception — it can't distinguish "recover with a fallback" logic that
also needs to know about the success value. `handle(BiFunction<T,Throwable,R>)`
always runs and sees both (one is null), making it the right tool for a
single terminal step that normalizes both outcomes into one return shape.

**Senior:** "A junior engineer's async pipeline calls `.get()` in the
middle of a `CompletableFuture` chain to 'get the intermediate result before
continuing.' What's wrong with this, and how do you fix it?"
*Ideal answer:* `.get()` BLOCKS the calling thread until the future
completes — calling it mid-chain (especially inside another async stage,
e.g. inside a `thenApply` lambda) ties up a pool thread waiting
synchronously, which defeats the entire purpose of using
`CompletableFuture` and, worse, can exhaust the pool under load if every
in-flight task is now blocking a thread waiting on another async operation
(a form of self-inflicted thread starvation/pseudo-deadlock). The fix:
express "then do X with the result" using `thenApply`/`thenCompose`, never
a blocking get, and only call `.get()`/`.join()` once, at the true end of
the pipeline (or not at all, if the caller itself is async and returns the
`CompletableFuture` further up the stack).
*Follow-up:* "What thread does an `exceptionally`/`handle` callback actually
run on?" → Whatever thread completed the PREVIOUS stage — often a pool
thread from whatever `Executor` was passed to `supplyAsync`/`thenApplyAsync`,
NOT necessarily the original caller's thread. Code inside these callbacks
that assumes caller thread-local state (e.g. security context, MDC logging
context) is available needs to explicitly propagate it, since it won't
transfer automatically.

**Scenario:** "Design an endpoint that needs to call three independent
downstream services (inventory, pricing, reviews) and combine their results
into one response, minimizing total latency."
*Ideal answer:* Fire all three calls concurrently via
`CompletableFuture.supplyAsync` (or their async client equivalents) rather
than sequentially, then combine with `CompletableFuture.allOf` (if you just
need to wait for all three, then read each separately) or chained
`thenCombine` calls (if you need to combine their VALUES into one object).
Total latency becomes roughly the SLOWEST of the three calls instead of the
SUM of all three sequential calls. Add per-call timeouts (`orTimeout`/
`completeOnTimeout` in modern `CompletableFuture`, or explicit
`Future.get(timeout)` handling) and a fallback/partial-response strategy for
when one downstream service is slow or down, so one failing dependency
doesn't take down the whole endpoint.

---

## Virtual Threads

**Beginner:** "What is a virtual thread, and how is it different from a
platform thread?"
*Ideal answer:* A platform thread is a thin wrapper around one real OS
thread — relatively expensive to create, with real kernel scheduling
overhead. A virtual thread is a lightweight, JDK-managed thread; the JVM
"mounts" it onto one of a small pool of carrier platform threads to
actually run, and "unmounts" it during blocking operations the JDK
understands (I/O, `Thread.sleep`, many `java.util.concurrent` blocking
calls), freeing the carrier to run a different virtual thread meanwhile.
You can have millions of virtual threads "blocked" at once with only a
handful of real OS threads underneath.

**Intermediate:** "Why do virtual threads help I/O-bound workloads but not
CPU-bound ones?"
*Ideal answer:* The benefit comes specifically from UNMOUNTING during
blocking operations — an I/O-bound task spends most of its time blocked
waiting, so unmounting frees its carrier thread to do other useful work
during that wait, letting far more tasks be "in flight" than there are real
OS threads. A CPU-bound task never blocks, so it never unmounts — it
occupies its carrier thread for its entire run, identical to how a platform
thread would occupy a real OS thread. Both are then bottlenecked equally by
the same number of CPU cores; virtual threads add a small scheduling
indirection there for no throughput gain.
*Follow-up:* "Given that, would you use
`Executors.newVirtualThreadPerTaskExecutor()` for a batch image-processing
job?" → No — image processing is CPU-bound; use a platform thread pool
sized to available CPU cores (`Executors.newFixedThreadPool(cores)` or the
common `ForkJoinPool` via parallel streams) instead.

**Senior:** "What is 'pinning' in the context of virtual threads, and why
does it matter?"
*Ideal answer:* If a virtual thread performs a blocking operation while
inside a `synchronized` block (or certain native/JNI calls), it does NOT
unmount from its carrier — it stays "pinned" for the block's duration,
because safely releasing and reacquiring a monitor lock across an unmount
isn't universally guaranteed. Under heavy `synchronized` usage in
virtual-thread-heavy code that ALSO does blocking I/O inside those blocks,
this can silently reintroduce carrier-thread exhaustion — exactly the
scalability problem virtual threads exist to solve — without any obvious
error, just degraded throughput under load. Mitigation: prefer
`java.util.concurrent.locks.ReentrantLock` over `synchronized` in code paths
that run on virtual threads and block while holding a lock (and verify
current pinning behavior for your exact JDK version, since it has evolved
across releases).
*Follow-up:* "How would you detect pinning in a running application?" →
JFR (Java Flight Recorder) has a specific event for virtual thread pinning
(`jdk.VirtualThreadPinned`) that can be captured and analyzed — a realistic
production-debugging follow-up tying into Module 11 (JVM internals).

**Scenario:** "Your team is migrating a Spring MVC (blocking, non-reactive)
service to virtual threads to handle more concurrent traffic without
rewriting to WebFlux. What do you check before flipping the switch, and
what do you tell the team NOT to expect?"
*Ideal answer:* Check for `synchronized` blocks around blocking calls
(pinning risk — audit and consider `ReentrantLock` replacements in hot
paths), check for artificial thread-pool-sizing assumptions baked into
config (some libraries/frameworks size internal behavior off
`Runtime.getRuntime().availableProcessors()` or a configured pool size,
assumptions that don't transfer cleanly to "one virtual thread per
request"), and check for `ThreadLocal`-heavy code that might allocate more
memory than expected at much higher concurrency. Set expectations correctly:
virtual threads raise how many concurrent IN-FLIGHT (mostly blocked)
requests the service can sustain — they do NOT make any individual
request's actual CPU work faster, and won't help at all if the service's
real bottleneck is downstream (a saturated database connection pool, for
instance, needs its OWN sizing fix regardless of the request-handling
thread model).

---

## Atomic Classes & Concurrent Collections

**Beginner:** "What's the difference between `AtomicInteger` and a plain
`int` guarded by `synchronized`, from a correctness standpoint?"
*Ideal answer:* Both can be made correct under concurrent access, but via
different mechanisms — `synchronized` uses blocking mutual exclusion (a
losing thread waits, parked, until the lock is free); `AtomicInteger` uses
lock-free compare-and-swap (a losing thread immediately retries its
read-compute-swap cycle, never blocking). Both prevent the same class of
lost-update bug; they differ in performance characteristics under
contention, not in the correctness guarantee itself.

**Intermediate:** "When would you choose `LongAdder` over `AtomicLong`?"
*Ideal answer:* Under HIGH write contention from many threads incrementing
the SAME counter, `LongAdder` internally stripes the count across multiple
cells so different threads' writes mostly land on different cells instead
of all CAS-racing one value — much better write throughput at the cost of a
slower `sum()` (which must add every cell). Use `AtomicLong` when you need
`compareAndSet`/`get`-and-reason-about-the-exact-current-value semantics, or
when contention is low-to-moderate and the simpler, always-fast `get()` is
preferable; use `LongAdder` for pure high-frequency counting (metrics,
request counts) under real contention where you never need to
read-and-immediately-act-on the exact value.

**Senior:** "Explain what `ConcurrentHashMap.compute()`, `AtomicReference.
compareAndSet()`, and `ReentrantLock` all have in common, and where they
genuinely differ."
*Ideal answer:* All three provide a way to make a check-then-act sequence
atomic under concurrent access — they're solving the SAME class of problem
at different granularities. `compareAndSet` is the primitive, lock-free
building block (CAS on a single reference/value). `compute()` is
effectively a CAS-style atomic update scoped to one map entry, built on the
same underlying idea but hiding the retry loop from the caller.
`ReentrantLock` (and `synchronized`) instead use blocking mutual exclusion —
correctness through exclusivity rather than optimistic retry. The genuine
difference that matters for choosing between them: CAS-based approaches
(the first two) don't compose across MULTIPLE independent pieces of state
(you can't atomically CAS two unrelated `AtomicReference`s together) — the
moment an operation must touch multiple pieces of state as one atomic unit,
you generally need a lock spanning all of them, which is exactly why
`StockTransferService` (needing two `Warehouse`s' state consistent together)
uses `ReentrantLock`, not `AtomicReference`/`compute()`.
*Follow-up:* "Tie this back to a database concept." → This is the exact
distinction between optimistic locking (a `@Version` column, retry on
conflict — the CAS approach applied to a database row) and pessimistic
locking (`SELECT ... FOR UPDATE`, an actual database-level lock held for a
transaction's duration) — Module 9 (databases) covers this in full; the
in-memory concurrency primitives here are the same idea one level down the
stack.

**Scenario:** "A metrics dashboard built on a single `AtomicLong` per
counter starts showing degraded application throughput under Black-Friday-
level traffic, even though the counters themselves are 'just incrementing a
number.' Diagnose and fix."
*Ideal answer:* At very high request volume, many threads are all
CAS-racing the SAME `AtomicLong` for each counter — under enough contention,
constant CAS-retry storms (every thread's swap attempt failing and retrying
because another thread's swap just succeeded) can measurably eat CPU and
create memory-bus contention (cache-line bouncing between cores), which is
a real, if often surprising, throughput cost purely from "incrementing a
number." Fix: replace hot per-request counters with `LongAdder` (this is
precisely the use case it was added to the JDK for), which stripes the
count across per-thread/per-core cells specifically to eliminate this
cache-line-contention hot spot; only aggregate (`sum()`) when actually
reading the dashboard value, which happens far less often than every
counter increment.
