# Module 3 — Exercises

Do these in order — each builds on the previous one's code and concepts.
Work directly in
`src/main/java/com/interviewprep/orders/concurrency/`. There's still no test
framework (JUnit arrives once Module 5 sets up Maven) — verify each exercise
by extending a `Demo` class's `main`/`run` method with a call that prints
the result and eyeballing it, the same way Module 1's exercises worked.
Remember: several of these exercises are inherently timing-dependent — if a
result looks "too clean" on the first run, re-run it a few times (or raise
thread/iteration counts) before concluding your fix (or your reproduction of
a bug) actually works.

## 1. (Beginner) Add a fourth `ReservableInventory` implementation: `synchronized` methods instead of an explicit lock object

`SynchronizedInventory` uses a private `Object lock` field and `synchronized
(lock) { ... }` blocks. Write a new class, `SynchronizedMethodInventory`,
that achieves the same coarse-grained thread safety using `synchronized`
directly on the instance methods (`public synchronized void reserve(...)`
etc.) instead. Run it through `InventoryStressTester` the same way
`ConcurrencyFixComparisonDemo` does and confirm it's equally correct.

**Check yourself:** what's the actual difference between `synchronized
void reserve(...)` and `synchronized (lock) { ... }` inside a non-synchronized
method — specifically, what object is being locked on in each case, and why
does the README (section 2) argue the explicit-lock-object version is safer
in a class other code might hold a reference to?

## 2. (Beginner) Multi-SKU stress test

`InventoryStressTester.stressTest` only ever tests ONE sku at a time — every
thread hammers the same key. Write a new test method (or a new demo class)
that spreads threads across MULTIPLE skus (e.g. 4 skus, 2 threads per sku,
each thread doing its own iterations against its own sku) and runs it
against `SynchronizedInventory` and `StripedLockInventory`. Time both.

**Check yourself:** does `SynchronizedInventory` get noticeably slower
relative to `StripedLockInventory` now, compared to the single-SKU stress
test in `ConcurrencyFixComparisonDemo`? If not, what would you need to
change (thread count? iteration count? sku count?) to make the difference
observable, and why does spreading contention across more DISTINCT keys
matter for a coarse-vs-fine-grained-locking comparison specifically?

## 3. (Intermediate) Add `reserveAll` to `ConcurrentInventory` — and confront its atomicity limit

Module 1's EXERCISES.md exercise 3 asked whether an all-or-nothing multi-SKU
`reserveAll(Map<String, Integer>)` belongs in `Inventory` or in
`OrderService`. Implement `reserveAll` on `ConcurrentInventory` using
`compute()` per SKU, called in a loop with manual rollback on failure
(exactly like `AsyncOrderProcessor.reserveAndCreateOrder`'s `Deque`-based
rollback). Then write a short comment answering: does using
`ConcurrentHashMap.compute()` for each individual SKU make the WHOLE
`reserveAll` call atomic across all SKUs together? (Hint: no — work out
precisely what interleaving with another thread's `reserveAll` call on an
OVERLAPPING set of SKUs could still go wrong, even though each individual
`compute()` call is perfectly atomic.)

## 4. (Intermediate) Convert `BatchOrderProcessingDemo` to Virtual Threads

`BatchOrderProcessingDemo` builds its `AsyncOrderProcessor` on top of
`Executors.newFixedThreadPool(4)`. Change it to use
`Executors.newVirtualThreadPerTaskExecutor()` instead, and re-run it.

**Check yourself:** the batch here is only 6 orders — would you actually
expect a measurable difference from this switch at this scale? Now imagine
the batch is 50,000 orders instead of 6, each with a `PaymentGateway.charge`
call that sleeps 50ms to simulate network latency. Reason through (you don't
need to actually run 50,000 orders) which executor finishes faster and by
roughly how much, using the same reasoning `VirtualThreadsDemo`'s I/O-bound
section walks through — then write down why this SAME reasoning would NOT
apply if `charge()` were replaced with a CPU-heavy fraud-scoring calculation
instead of a `Thread.sleep`.

## 5. (Senior) Detect the race condition automatically, without eyeballing output

`RaceConditionDemo` prints its result and a human reads whether it says
"CONSISTENT" or not. Write a method `boolean detectsRaceReliably(int
attempts)` that runs `InventoryStressTester.stressTest` against
`UnsafeInventoryAdapter` repeatedly (up to `attempts` times) and returns
`true` the first time `isConsistent()` is `false` (bug reproduced), `false`
if it never reproduces within the attempt budget. Run it and report how many
attempts it typically takes on your machine. Then do the same for
`ConcurrentInventory`, `SynchronizedInventory`, and `StripedLockInventory` —
they should NEVER return `true` (never show the bug) no matter how many
attempts you give them. This is, in miniature, exactly what a real
concurrency-focused CI stress-test job does (run the suspect code path many
times under load, fail the build if inconsistency is ever observed even
once) — write a sentence explaining why a single passing test run is
categorically weaker evidence of thread-safety than this repeated-attempts
approach, even though both "pass" in the everyday sense.

## 6. (Scenario) A three-warehouse transfer chain — extending the deadlock fix

Product now wants stock transfers to be able to go through a THIRD
warehouse as a waypoint in one atomic-looking operation: move stock from
`WH-EAST` to `WH-CENTRAL` to `WH-WEST`, holding all three warehouses' locks
for the duration of both hops (so no other thread can observe the
intermediate "in `WH-CENTRAL`, not yet in `WH-WEST`" state). Add a method
`transferChained(Warehouse first, Warehouse middle, Warehouse last, String
sku, int quantity)` to `StockTransferService` that acquires all three locks
safely.

Think through, and write down your reasoning for:
- Does `transferOrdered`'s two-warehouse approach (sort by `id()`, lock in
  that order) generalize cleanly to three warehouses? What do you sort by,
  and does the transfer logic (which warehouse loses stock, which gains it,
  in what order) need to change, or only the LOCK acquisition order?
- Two threads could call `transferChained` with overlapping warehouse sets
  in different roles (e.g. thread A's `middle` is thread B's `first`) —
  walk through whether your consistent-ordering fix still prevents deadlock
  in that case, and why (or why not).
- `DeadlockDemo` proved its two-warehouse deadlock using
  `ThreadMXBean.findDeadlockedThreads()`. If your first attempt at
  `transferChained` had a bug that could deadlock, would that same detection
  code work unmodified to catch it? What would you need to add to
  `DeadlockDemo` (or a new demo) to actually exercise and verify a
  three-warehouse deadlock scenario, given that `transferUnsafe` in this
  module is written for exactly two warehouses?
