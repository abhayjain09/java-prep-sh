# Module 3 — Multithreading & Concurrency

**Domain used throughout:** the same Order/Inventory system from Module 1 (`Customer`, `Product`, `Order`, `OrderLine`, `Inventory`). This module does not introduce a new toy example — it takes the exact class Module 1 flagged as a landmine (`Inventory.reserve()`, documented there as having a deliberate, unfixed race condition) and spends the whole module fixing it, then builds everything else (deadlocks, async pipelines, virtual threads, atomics, concurrent collections) on top of the same domain.

Companion files:
- [diagrams/race-condition-sequence.md](diagrams/race-condition-sequence.md) — interleaving diagram of the oversell bug
- [diagrams/deadlock-sequence.md](diagrams/deadlock-sequence.md) — interleaving diagram of the two-lock deadlock
- [src/](src/main/java/com/interviewprep/orders/concurrency) — the actual code
- [EXPLANATION.md](EXPLANATION.md) — line-by-line walkthrough of every file in `src/`
- [EXERCISES.md](EXERCISES.md) — hands-on exercises
- [INTERVIEW.md](INTERVIEW.md) — beginner/intermediate/senior/scenario interview questions with ideal answers

## How to build and run this module

This sandbox has no JDK installed, so none of this code has been compiled. Run these commands yourself locally (Java 21+ required — Virtual Threads, `ExecutorService.close()`, and switch/record features used elsewhere in the repo all need it):

```bash
# from the repo root, compile BOTH source roots together — this module
# imports domain classes directly from java-basics/, it does not duplicate them
javac -d out $(find java-basics/src/main/java java-advanced/concurrency/src/main/java -name "*.java")

# run the full walkthrough (all 7 demos in sequence)
java -cp out com.interviewprep.orders.concurrency.Main

# or run any single demo standalone, e.g.:
java -cp out com.interviewprep.orders.concurrency.RaceConditionDemo
java -cp out com.interviewprep.orders.concurrency.DeadlockDemo
java -cp out com.interviewprep.orders.concurrency.VirtualThreadsDemo
```

**A note on non-determinism:** several demos in this module (`RaceConditionDemo`, `DeadlockDemo`) reproduce genuine timing-dependent bugs. They are written to make the bug likely to show up (many threads, many iterations, deliberate `Thread.sleep` calls that widen interleaving windows), but "likely" is not "guaranteed" — if a run doesn't show the bug, re-run it, or raise the thread/iteration counts noted in each file's `main`. This unpredictability is itself one of the most important lessons of this module: concurrency bugs routinely pass code review and a handful of manual test runs, then surface for the first time under production load.

---

## 1. Race Conditions

### What it is
A race condition is a bug where the correctness of a program depends on the relative TIMING of multiple threads — specifically, on whether one thread's operations happen to interleave with another's in a way the code didn't account for. `Inventory.reserve()` (Module 1) is the canonical example: it reads `stockBySku`, checks the value, then writes back a new value — three separate steps with no guarantee nothing else runs in between.

### Why introduced
This module exists because Module 1's `Inventory` was built and left deliberately broken under concurrency — its Javadoc says so explicitly. Every enterprise Java system eventually has shared mutable state accessed by more than one thread (a web server handling concurrent requests is the most common shape), so recognizing this bug pattern on sight is a baseline expectation at senior level, not an advanced topic.

### The problem it solves (understanding, then fixing)
The problem being SOLVED here is over-selling: two customers both being told "yes, that's in stock" for the last unit, and both purchases succeeding, when only one unit existed. In terms of code: two threads calling `reserve("SKU-X", 1)` when `stockBySku.get("SKU-X") == 1` can BOTH execute `int available = stockOf(sku);` and read `1` before EITHER executes `stockBySku.put(sku, available - quantity);`. Both threads then independently decide "1 >= 1, this reservation is fine" and both write `stockBySku.put(sku, 0)`. One unit of stock was reserved twice. See [diagrams/race-condition-sequence.md](diagrams/race-condition-sequence.md) for the exact interleaving.

`RaceConditionDemo` (`src/.../RaceConditionDemo.java`) reproduces this on demand: it hammers a single SKU with 8 threads × 200 `reserve(sku, 1)` calls each (1600 attempts against 500 units of stock) via `InventoryStressTester`, a shared harness that releases all threads through a `CountDownLatch` "starting gate" simultaneously to maximize interleaving, then reports whether `successCount == initialStock && finalStock == 0` held (it should, always, if reservations were atomic).

**Why a stress-test loop, not two threads and one call each:** the read-check-write window in `reserve()` is a handful of nanoseconds wide. A single pair of concurrent calls has a real but small chance of actually landing inside that window. Many threads × many iterations dramatically raises the odds that SOME pair of calls collides inside the window during the run — this is a probabilistic argument, not a guarantee, which is why `RaceConditionDemo`'s own output explicitly tells you a clean run is not proof of a fix.

### When this class of bug shows up (When to look for it)
Any time more than one thread can reach the SAME mutable state without synchronization: shared counters, caches, in-memory maps used as "the source of truth" (exactly `Inventory`'s shape), singleton services with mutable fields, lazily-initialized singletons (`if (instance == null) instance = new Thing();` is this exact bug shape applied to object creation).

### When NOT to worry about it
Single-threaded code paths (a CLI script, `Main.java` in Module 1, a batch job with one worker) have no race to have — synchronizing there adds pure overhead and no benefit. Immutable data (records, `final` fields never reassigned after construction, defensively-copied collections like `Order.getLines()`) can be freely shared across threads with zero synchronization, because there's no mutation for two threads to race on. The single biggest concurrency-related performance win available to most codebases is simply preferring immutability over synchronized mutability wherever the domain allows it.

### Trade-offs & performance implications
Detecting races is genuinely hard: they often don't reproduce on a developer's laptop (fewer cores, different scheduling), don't reproduce under a debugger (breakpoints change timing enough to mask the bug — the "heisenbug" phenomenon), and may only manifest under real production concurrency load after months in service. Tools that help: stress-test loops like this module's (cheap, in-house, no tooling needed), and static analysis (e.g. IntelliJ's thread-safety inspections, `SpotBugs`' concurrency detectors), which can flag some patterns (unsynchronized read-modify-write on a shared field) without ever running the code.

### Enterprise examples
This exact bug (with real money attached) is why "flash sale" / limited-inventory e-commerce features are a classic root cause of production incidents — a sale with 100 units and enough concurrent traffic reliably oversells without a fix like the ones in this module. Ticket-booking systems (concert seats, plane seats) are the other textbook case interviewers reach for.

### Common mistakes
- Believing "I tested it manually and it worked" is evidence of thread-safety — it's evidence the specific interleaving you happened to trigger was fine, nothing more.
- Assuming swapping `HashMap` for `ConcurrentHashMap` alone fixes a read-modify-write bug (it does NOT — see `ConcurrentInventory`'s Javadoc for exactly why: `ConcurrentHashMap` makes each INDIVIDUAL call thread-safe, not a SEQUENCE of calls).
- Adding `synchronized` to only SOME of the methods that touch shared state (e.g. synchronizing `reserve()` but not `restock()`) — this provides no real protection, since an unsynchronized method can still interleave with a synchronized one's critical section.

---

## 2. Fixing the Race: `compute()`, Locks, and Lock Granularity

This module ships three interchangeable fixes, all implementing a shared `ReservableInventory` interface so `InventoryStressTester` can run the identical concurrent workload against each one (see `ConcurrencyFixComparisonDemo`).

### What each fix is
- **`ConcurrentInventory`** — backed by `ConcurrentHashMap`, uses `compute(sku, (key, current) -> ...)` to make the ENTIRE check-then-decrement one atomic operation per key. The JDK holds an internal per-bin lock for the duration of the remapping function; no other thread can observe or mutate that key while it runs. `compute()` is also specified so that if the remapping function throws, the map entry is left completely unchanged — "reserve either fully succeeds or has zero effect" comes for free.
- **`SynchronizedInventory`** — a single `Object lock` guards EVERY method with `synchronized (lock) { ... }`. Coarse-grained: correctness is trivial to see (only one thread ever touches inventory state, full stop), but every SKU serializes behind every other SKU even when they're unrelated.
- **`StripedLockInventory`** — a fixed-size array of `ReentrantLock`s (16 by default); each SKU hashes to one stripe (`Math.floorMod(sku.hashCode(), stripes.length)`). Different SKUs usually land on different stripes and run fully in parallel; SKUs that happen to collide on the same stripe serialize (a "false" collision, the cost of a bounded-size lock table).

### Why introduced / problem each one solves
All three exist to solve the SAME problem (the race in section 1) with different trade-offs between simplicity, throughput, and applicability:
- `compute()` needs the least code and is the most idiomatic modern-Java answer when your unit of atomicity is "one key in one map."
- `synchronized` needs no `java.util.concurrent` API knowledge at all — the oldest, simplest tool, correct as long as coarseness is acceptable.
- Striped locks are needed the moment you must hold MULTIPLE locks for one logical operation (see `StockTransferService` in section 3) — `compute()`'s atomicity boundary is strictly per-key, it cannot span two keys or two different `ConcurrentInventory` instances in one atomic step.

### Lock granularity — the trade-off this section is really about
"Granularity" is how much state one lock protects. **Coarse-grained** (one lock, whole map — `SynchronizedInventory`) is simple to reason about but serializes unrelated work: two threads reserving `SKU-LAPTOP` and `SKU-MOUSE` block each other for no real reason. **Fine-grained** (one lock per key or per stripe — `ConcurrentInventory`, `StripedLockInventory`) lets unrelated operations run truly in parallel, raising throughput under multi-key contention — at the cost of more subtle reasoning, and, critically, at the cost of introducing DEADLOCK risk the moment an operation needs to hold more than one fine-grained lock at once (section 3 exists entirely because of this).

### When to use which
- Single shared counter/map with no multi-key or cross-object operations, low-to-moderate contention → `ConcurrentHashMap.compute()`/`merge()` (`ConcurrentInventory`'s approach). Least code, least risk.
- Need to reason about invariants spanning MULTIPLE keys or fields together in one operation, and throughput under contention isn't critical → a single lock (`SynchronizedInventory`'s approach). Easiest to verify correct.
- High contention across many independent keys, and you've measured (not guessed) that a single lock is a bottleneck → striped or per-key locking (`StripedLockInventory`'s approach) — but budget real code-review attention for lock-ordering risk (section 3) the moment more than one stripe/lock is held at once.

### When NOT to use / common mistakes
- Don't reach for striped/per-key locking as a default "for performance" without measuring — it's real complexity for a real throughput gain that only exists under genuine multi-key contention; under this module's own single-SKU stress test, all three fixes perform comparably because every thread contends on the SAME key regardless of strategy (see `ConcurrencyFixComparisonDemo`'s output commentary).
- The unbounded "one `ReentrantLock` per SKU ever seen, cached forever in a `ConcurrentHashMap`" design (tempting because it has ZERO false collisions) is a real memory leak in a long-running service — this is why `StripedLockInventory` uses a FIXED-size array instead. This trade-off (bounded memory vs. zero false contention) is exactly what `java.util.concurrent`'s internal `Striped64`/`LongAdder` machinery and Guava's `Striped` utility class both make explicitly, for the same reason.
- Forgetting the `finally { lock.unlock(); }` around a manually-acquired `ReentrantLock` — an exception mid-critical-section then leaves the lock held forever, and every other thread waiting on it blocks permanently. `synchronized` cannot have this bug (the JVM releases the monitor automatically on any exit, including via exception) — one real argument for preferring `synchronized` when you don't need `ReentrantLock`'s extra capabilities.

### Trade-offs & performance implications
`compute()`/`merge()` on `ConcurrentHashMap` has effectively the finest possible granularity (per-bin) for free, with no lock-management code to get wrong. `synchronized` is JIT-optimized extremely well for the UNCONTENDED case (biased/thin locking) — its real cost only shows up under actual contention, which is exactly where coarse-graining hurts most. `ReentrantLock` adds a small object-allocation and indirection cost over `synchronized` but buys `tryLock(timeout)` and `lockInterruptibly()` — capabilities `synchronized` simply does not have, and which `StockTransferService.transferWithTimeout` (section 3) genuinely needs.

### Enterprise examples
Payment processors and inventory systems at scale commonly use per-account or per-SKU locking (striped or key-based) specifically because a single global lock on "all accounts" or "all inventory" would make the system's throughput ceiling one thread's worth of work, regardless of how many CPU cores or servers are available — a common root cause when a legacy system "can't scale past X requests/second no matter how much hardware you add."

---

## 3. Deadlocks

### What it is
A deadlock is a set of threads each holding a resource (a lock) that another thread in the same set is waiting for, forming a cycle with no way out — no thread can ever proceed. The classic minimal example: thread A holds lock 1, wants lock 2; thread B holds lock 2, wants lock 1. Neither ever releases what they hold before getting what they want.

### Why introduced
The moment section 2 introduces fine-grained locking (multiple distinct locks in the system), any operation that needs to hold MORE than one of them at once creates deadlock risk — this is the direct, structural consequence of solving the race-condition problem with finer granularity. `StockTransferService` needs both warehouses' locks at once (you cannot let another thread observe "stock removed from A" without "stock added to B" having also happened), which is exactly the shape that creates the risk.

### Problem it solves (by demonstrating the failure, then two fixes)
`StockTransferService.transferUnsafe(from, to, sku, quantity, holdDelayMillis)` locks `from` then `to` — in CALLER-SUPPLIED order, with no global consistency. `DeadlockDemo.demonstrateDeadlock()` runs `transferUnsafe(east, west, ...)` on one thread and `transferUnsafe(west, east, ...)` concurrently on another: thread 1 locks `east`, thread 2 locks `west`, then thread 1 blocks waiting for `west` (held by thread 2) while thread 2 blocks waiting for `east` (held by thread 1) — permanent circular wait. See [diagrams/deadlock-sequence.md](diagrams/deadlock-sequence.md).

**Detecting it like production tooling does:** rather than just asserting "this would deadlock," `DeadlockDemo` actually launches the two threads (as daemon threads — see the class Javadoc for why) and polls `ThreadMXBean.findDeadlockedThreads()` — the same underlying mechanism `jstack -l <pid>` and IDE/VisualVM thread-dump deadlock detection use — printing the exact lock each thread holds and is waiting for once detected.

**Fix #1 — consistent lock ordering** (`transferOrdered`): every caller, regardless of transfer direction, acquires locks in the SAME global order (here, by comparing `Warehouse.id()`). If every thread that needs locks A and B always takes A before B, a cycle is structurally impossible — one direction of the circular wait simply cannot occur. This is the standard, preferred fix whenever a stable ordering key exists (an ID, a `compareTo`, even `System.identityHashCode()` as a last resort).

**Fix #2 — `tryLock` with timeout** (`transferWithTimeout`): when no natural global order exists, bound the wait on each lock instead. If both locks aren't acquired within the timeout, release whatever's held and retry (with randomized backoff to avoid livelock — two threads retrying in perfect lockstep forever). This trades a small amount of wasted retry work for the guarantee that no thread ever blocks indefinitely.

### When to use which fix
- A stable, total ordering key exists across the resources in question (IDs, natural keys, or even just object identity hash as a fallback) → lock ordering. Zero runtime overhead once implemented, deadlock becomes structurally impossible rather than merely unlikely.
- No natural ordering exists, or the resources come from independent subsystems that can't agree on one → `tryLock` with timeout. Slightly more runtime overhead (polling/retry) and requires designing a sensible retry/give-up policy, but works when ordering doesn't.

### When NOT to rely on either
Neither fix helps if the SAME thread re-enters a lock it already holds in a way that creates a NEW cycle through a different resource (a rarer, harder-to-spot deadlock shape) — that requires careful code review of everything that runs while a lock is held, not just the two fixes here. Also: never treat "I tested it and it didn't deadlock" as proof — like race conditions, deadlocks are timing-dependent; `transferUnsafe`'s `holdDelayMillis` exists specifically because a real deadlock here would otherwise only occasionally reproduce.

### Trade-offs & performance implications
Lock ordering costs nothing at runtime but requires DISCIPLINE across the whole codebase — every future call site that needs both locks must respect the same order, forever, including code written by someone who's never read this module. `tryLock`-with-timeout costs real CPU on retries under contention and adds latency (best case: no retries needed; worst case: repeated timeout-and-backoff cycles), but degrades gracefully instead of hanging.

### Enterprise examples
The classic "transfer money between two bank accounts" interview question is this exact pattern with `Account` instead of `Warehouse` — expect it, in some form, at almost any senior Java interview that covers concurrency at JPMorgan, Goldman Sachs, or S&P Global specifically because financial systems are full of genuine multi-resource-locking operations (transfers, netting, settlement) where getting this wrong has direct monetary consequences.

### Common mistakes
- Reaching for `tryLock`-with-timeout as a "safety net" while ALSO having inconsistent lock ordering elsewhere in the same codebase — timeouts mask the symptom (the program eventually gives up instead of hanging forever) without fixing the underlying design flaw, and can turn a deadlock into a much-harder-to-diagnose intermittent failure instead.
- Holding a lock across a call to unrelated code you don't control (a callback, a listener, an external library call) — if that code ever tries to acquire a second lock, you've created a hidden two-lock dependency nobody who wrote either piece of code was aware of.
- Calling `Thread.interrupt()` expecting it to free a thread blocked in plain `lock.lock()` — it does not; only `lockInterruptibly()` responds to interrupts while waiting. This is exactly why `DeadlockDemo` documents that its two deadlocked demo threads are truly, permanently stuck (mitigated only by being daemon threads that don't block JVM exit).

---

## 4. Executors & CompletableFuture

### What it is
`ExecutorService` decouples "what work needs doing" (a `Runnable`/`Callable`) from "how many threads run it" (the pool implementation and size) — you submit tasks, the executor manages the thread lifecycle. `CompletableFuture` represents a value that will be available LATER (asynchronously), with a rich API for chaining further computation onto it without blocking the submitting thread to wait for the result.

### Why introduced / problem it solves
Before executors, you managed `Thread` objects directly — creating, starting, and joining them by hand, with no reuse (creating a new OS thread per unit of work is expensive) and no back-pressure control (nothing stops you from creating ten thousand threads at once). `ExecutorService` (specifically `Executors.newFixedThreadPool(n)` in `BatchOrderProcessingDemo`) reuses a bounded pool of worker threads across many submitted tasks. Before `CompletableFuture`, composing a sequence of async steps ("do A, then when it finishes do B with A's result, then C") meant either blocking on `Future.get()` between every step (defeating the purpose of async) or hand-rolling callback chains (verbose, error-prone, the "callback hell" every async-heavy language eventually hits).

### The pipeline this module builds (`AsyncOrderProcessor`)
```
supplyAsync(reserveAndCreateOrder)          // stage 1: fast, in-memory, on the pool
    .thenCompose(order ->
        paymentGateway.chargeAsync(order)        // stage 2: slow, I/O-bound, on the pool
            .thenApply(payment -> confirm(order, payment))          // stage 3: success path
            .exceptionally(ex -> cancelAfterPaymentFailure(order, ex)))  // stage 2/3 failure path
    .handle((outcome, ex) -> ex == null ? outcome : failedBeforePayment(ex));  // final normalize
```
- **`thenApply`** transforms a completed value with a synchronous function (`payment -> confirm(order, payment)`) — used when the next step is NOT itself asynchronous.
- **`thenCompose`** ("flatMap" for futures) chains a step whose OWN result is itself a `CompletableFuture` (`chargeAsync` returns one) — using `thenApply` here instead would nest futures (`CompletableFuture<CompletableFuture<PaymentResult>>`), almost never what you want.
- **`exceptionally`** runs ONLY on failure, sees ONLY the exception, and produces a recovery value — used for the ONE specific failure mode (payment declined after stock was already reserved) that needs a specific recovery action (release that stock).
- **`handle`** ALWAYS runs, sees BOTH the value and the exception (exactly one is `null`) — used as the pipeline's single terminal step to normalize every remaining failure path (reservation failing before payment was ever attempted) into the same `OrderOutcome` return type the success path produces.
- Exceptions thrown inside `supplyAsync`/`thenApply`/etc. arrive at downstream stages wrapped in a `CompletionException` — `AsyncOrderProcessor.rootCause()` unwraps it so error messages show the real business exception (`InsufficientStockException`, `PaymentDeclinedException`), not a generic wrapper with no useful message.

`BatchOrderProcessingDemo` processes six orders concurrently across a `newFixedThreadPool(4)`, deliberately seeding one order that exceeds available stock and one whose total exceeds the payment gateway's simulated decline threshold, so every branch of the pipeline actually executes in a single run. `CompletableFuture.allOf(futures).join()` is the synchronization point that waits for the WHOLE batch before the demo reads and prints each individual (already-resolved) result.

### When to use / when not to use
Use `ExecutorService` any time you have many independent units of work and want bounded, reusable thread pools instead of ad-hoc `new Thread()` per task. Use `CompletableFuture` when you need to CHAIN or COMBINE multiple async steps, especially I/O-bound ones (network calls, database queries) where blocking a thread for the duration would waste it. Don't reach for either for a single, one-off blocking call with no composition need — a plain synchronous call is simpler and equally correct. Don't use `CompletableFuture.get()` (blocking, unbounded) inside another async stage — you'd tie up a pool thread waiting on a blocking call, defeating the purpose; use `thenCompose`/`thenApply`/`handle` to stay non-blocking end to end.

### Trade-offs & performance implications
An `ExecutorService`'s thread pool must be explicitly shut down (`shutdown()`/`awaitTermination()` — see every demo's `finally` block) or the application won't exit cleanly (non-daemon threads keep the JVM alive). Sizing a fixed pool matters: too small under-utilizes available parallelism for CPU-bound work; too large under I/O-bound work wastes memory on idle threads waiting on I/O (a `newFixedThreadPool(100)` for I/O-bound tasks is exactly the scenario section 5 shows Virtual Threads solving far more cheaply). `CompletableFuture` chains that swallow exceptions silently (`exceptionally` returning a value without logging) can hide real failures — always log inside a recovery handler, not just return a fallback.

### Enterprise examples
Any REST API endpoint that needs to call multiple downstream services and combine their results (a product page needing inventory + pricing + reviews from three separate services) is a canonical `CompletableFuture.allOf`/`thenCombine` use case — fetch all three concurrently instead of sequentially, cutting total latency to the SLOWEST call instead of the SUM of all three.

### Common mistakes
- Blocking (`.get()`/`.join()`) too early in a chain — turns an async pipeline back into a synchronous one and defeats the purpose; only block at the very end (or not at all, if the caller is itself async).
- Forgetting that `.exceptionally()`/`.handle()` callbacks run on whatever thread completed the previous stage (often a pool thread, NOT the original caller's thread) — code inside them that assumes "the caller's thread-local state" is available will silently misbehave.
- Not shutting down an `ExecutorService`, leaving the JVM hanging on exit (or, in a long-running server, silently accumulating thread pools if one is created per-request instead of once and reused).

---

## 5. Virtual Threads (Java 21 / JEP 444)

### What it is
A virtual thread is a lightweight, JDK-managed thread that is NOT a 1:1 wrapper around a real OS thread. The JVM "mounts" a runnable virtual thread onto one of a small pool of CARRIER platform threads (sized to CPU cores by default) to actually execute; when the virtual thread performs a blocking operation the JDK knows how to cooperate with (I/O, `Thread.sleep`, blocking on most `java.util.concurrent` locks/queues as of Java 21), it "unmounts" from its carrier, freeing that carrier to run a DIFFERENT virtual thread. `Executors.newVirtualThreadPerTaskExecutor()` gives every submitted task its own brand-new virtual thread instead of pulling from a fixed pool.

### Why introduced / problem it solves
The traditional "thread-per-request" server model (one platform thread blocked for the duration of each request, most of that time spent waiting on a database or downstream call) hits a hard ceiling: platform threads are expensive (roughly 1MB default stack, real OS scheduling overhead), so a server can only sustain a few thousand CONCURRENT in-flight requests before running out of threads — even though each request's actual CPU work might be milliseconds, the rest is pure waiting. Virtual threads let you write the exact same simple, blocking, synchronous-looking code (no reactive/callback style needed) while supporting millions of concurrent in-flight blocking operations, because a blocked virtual thread costs almost nothing (no OS thread tied up) — only the CPU work costs real, limited carrier-thread time.

### The comparison this module runs (`VirtualThreadsDemo`)
**I/O-bound workload:** 2,000 tasks, each doing `Thread.sleep(30)` to simulate a blocking network/DB call. A `newFixedThreadPool(100)` must run them in ~20 queued batches of 100 (roughly 20 × 30ms ≈ 600ms total); `newVirtualThreadPerTaskExecutor()` runs effectively all 2,000 "in flight" via unmounting during the sleep, finishing in roughly one sleep duration's worth of wall-clock time. **CPU-bound workload:** a tight `for` loop doing real arithmetic, no blocking calls at all — here, both executors perform comparably, because a CPU-bound virtual thread never unmounts (there's nothing for it to yield during), so it occupies its carrier thread for its ENTIRE run, identical to how a platform thread would occupy a real OS thread. Both are ultimately bottlenecked by the same number of CPU cores.

### When to use / when NOT to use
Use virtual threads for I/O-bound, blocking-heavy workloads with high concurrency: web request handlers, calls to databases/downstream services/message queues — anywhere a thread would otherwise spend most of its life blocked waiting. **Do NOT** expect any benefit for CPU-bound work (data processing, number crunching, image/video encoding) — virtual threads add a small scheduling indirection there for zero throughput gain, since the real bottleneck (CPU cores) is identical either way; a correctly-sized platform thread pool is just as good and conceptually simpler for pure CPU-bound work. Also do not use virtual threads as a "pool" the way you'd size a platform thread pool — the whole point is you create one PER TASK and let the JDK manage them; artificially capping virtual thread creation (e.g. via a `Semaphore`) reintroduces the exact bottleneck they're meant to remove, unless you're specifically rate-limiting calls to a downstream system that itself has a concurrency limit.

### Trade-offs & performance implications
**Pinning:** if a virtual thread performs a blocking operation while inside a `synchronized` block (or certain native/JNI calls), it does NOT unmount — it stays pinned to its carrier thread for the block's duration, because releasing and re-acquiring a monitor lock across an unmount isn't safely possible in all JDK versions. Under heavy use of `synchronized` in virtual-thread-heavy code, this can silently reintroduce the "carrier thread pool exhaustion" problem virtual threads exist to avoid — the standard mitigation is preferring `java.util.concurrent.locks.ReentrantLock` over `synchronized` in code paths that run on virtual threads and perform blocking calls while holding a lock (later JDKs have progressively reduced pinning scenarios; verify current behavior for your exact JDK build before assuming it's fixed). **Thread-locals:** virtual threads support `ThreadLocal`, but creating millions of them means millions of thread-local instances if used carelessly — a pattern that was cheap at "a few hundred platform threads" scale can become a real memory concern at "hundreds of thousands of virtual threads" scale.

### Enterprise examples
Spring Boot 3.2+ has built-in virtual-thread support for its embedded Tomcat/Jetty request-handling model specifically to let existing blocking (non-reactive) Spring MVC controllers scale to far higher concurrent-request counts without a rewrite to WebFlux/reactive style — a direct, practical example of exactly the workload shape this module's I/O-bound demo simulates.

### Common mistakes
- Assuming virtual threads make code "faster" in a general sense — they improve CONCURRENCY (how much can be in flight at once) for blocking workloads, not raw computation speed.
- Wrapping CPU-bound batch/data-processing work in `newVirtualThreadPerTaskExecutor()` "to modernize it" and being confused when there's no improvement (see the CPU-bound half of `VirtualThreadsDemo`).
- Not knowing about pinning and being surprised when a virtual-thread-heavy service under load behaves like it has far fewer usable threads than expected — this is a realistic senior-level "gotcha" follow-up question once a candidate says "virtual threads solve thread-per-request scaling."

---

## 6. Atomic Classes & Concurrent Collections

### What they are
- **`AtomicInteger`/`AtomicLong`/`AtomicReference`** — lock-free thread-safe containers for a single value, built on the CPU's compare-and-swap (CAS) instruction: read the current value, compute a new one, attempt to atomically swap old-for-new, and retry the whole read-compute-swap cycle if another thread's swap won the race first. No thread ever blocks another.
- **`ConcurrentHashMap`** — a thread-safe map with per-bin (not whole-map) locking internally, plus atomic compound operations (`compute`, `merge`, `computeIfAbsent`) — the backbone of `ConcurrentInventory` (section 2).
- **`CopyOnWriteArrayList`** — a thread-safe list where every mutation copies the entire backing array; reads/iteration never block and never throw `ConcurrentModificationException`, because an iterator sees a fixed snapshot.
- **`BlockingQueue`** (`ArrayBlockingQueue` used here) — a thread-safe queue whose `put()`/`take()` BLOCK when the queue is full/empty respectively, the standard building block for producer/consumer pipelines.

### Why introduced / problem each solves
`AtomicCountersDemo` first reproduces the plain-`long`-counter race (`counter[0]++` from 8 threads — the same read-modify-write bug shape as `Inventory.reserve()`, just on a raw number) to make the point that this bug pattern isn't specific to `Inventory` — it's a general shape. `AtomicLong`/`AtomicInteger` solve it for single-value counters without needing a lock at all. `AtomicReference` generalizes the same CAS-retry idiom to arbitrary object references — demonstrated via a manual `compareAndSet` retry loop scanning for the SKU with the lowest stock, the same underlying mechanism every other Atomic method (and `ConcurrentHashMap.compute()`) is built from.

`ConcurrentCollectionsDemo` gives each concurrent collection its own concrete, distinct use case rather than a generic "here's the API": `ConcurrentHashMap<String, LongAdder>` for high-fan-in per-SKU attempt counters (see below for why `LongAdder`, not `AtomicLong`, under contention); `CopyOnWriteArrayList` for an append-only reservation audit log that's written occasionally and read/iterated far more often; `BlockingQueue` as a bounded order-intake pipeline where multiple producer threads submit incoming order requests and a single consumer thread drains and processes them sequentially through `OrderService.placeOrder` — notably, using Module 1's plain (unsafe) `Inventory`, which is perfectly safe here because exactly one thread ever touches it.

### When to use which
- Single counter/flag/reference, no compound multi-step logic needed → the matching `Atomic*` class.
- A counter under GENUINELY HIGH write contention from many threads, where you never need `compareAndSet` semantics, only the running total → `LongAdder`/`DoubleAdder` over `AtomicLong`/`AtomicDouble` — internally striped across cells to reduce CAS contention, at the cost of a slower `sum()` (fine, since reads are rare relative to writes in this use case).
- Map with per-key atomic compound operations needed, and/or high read/write concurrency across many keys → `ConcurrentHashMap`.
- A collection that's read/iterated far more often than it's mutated, and iteration must never throw `ConcurrentModificationException` → `CopyOnWriteArrayList`.
- Decoupling producers from a consumer (or multiple consumers) with built-in backpressure → `BlockingQueue` (bounded, via `put()`/`take()`).

### When NOT to use / common mistakes
- `CopyOnWriteArrayList` for anything write-heavy — every single `add()` copies the WHOLE backing array; at scale this is far worse than a normal `ArrayList` guarded by a lock or a genuinely concurrent structure.
- An unbounded queue "to be safe" — removes backpressure entirely, letting a slow consumer facing a fast producer grow the queue until the JVM runs out of memory; a bounded queue turning that into a producer stall (via blocking `put()`) is usually the correct trade.
- Reaching for `AtomicReference<SomeMutableObject>` and then mutating the OBJECT the reference points to directly (bypassing `compareAndSet`/`updateAndGet`) — this reintroduces an ordinary, unguarded race on the object's own fields; `AtomicReference` only makes swapping WHICH object the reference points to atomic, it does nothing for that object's internal mutable state.
- Using `AtomicLong` for a counter under heavy multi-threaded contention and being surprised it doesn't scale as well as expected — this is exactly the case `LongAdder` exists for; know when to reach for it instead.

### Trade-offs & performance implications
CAS-based (Atomic) operations tend to outperform locking under LOW-to-MODERATE contention (a "loser" thread just retries immediately instead of parking/blocking and later being woken by the OS scheduler — real, measurable overhead locking pays and CAS doesn't). Under VERY HIGH contention on a single Atomic value, constant CAS-retry storms can make plain locking — or `LongAdder`'s striping — win instead; this is a genuine "measure, don't assume" situation. `ConcurrentHashMap`'s finer internal locking means far better multi-key throughput than `Collections.synchronizedMap(new HashMap<>())` (whole-map locking) under real concurrent load.

### Enterprise examples
Metrics/observability libraries (Micrometer, Dropwizard Metrics) use `LongAdder`-style striped counters internally for exactly the high-write-contention counting case (request counts, latency histograms) described above. `BlockingQueue`-based producer/consumer pipelines back real message-processing systems as a simple, in-process alternative to (or often a component alongside) an external message broker like SQS/Kafka — Module 12+ (AWS) revisits this same pattern with SQS as the durable, cross-process version of the in-memory queue used here.

### Common mistakes
- See the "When NOT to use" mistakes above — the single most common interview trip-up in this section is not knowing `LongAdder` exists and reaching for `AtomicLong` unconditionally regardless of contention level.
