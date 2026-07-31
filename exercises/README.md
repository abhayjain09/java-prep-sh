# exercises/

Top-level index of exercises across all modules. Each module keeps its own detailed `EXERCISES.md`; this file is just a running index for quick navigation.

## Index

| Module | Exercises |
|---|---|
| 1 — Java basics | [java-basics/EXERCISES.md](../java-basics/EXERCISES.md) |
| 2 — File System APIs | [java-advanced/file-io/EXERCISES.md](../java-advanced/file-io/EXERCISES.md) |
| 3 — Concurrency | [java-advanced/concurrency/EXERCISES.md](../java-advanced/concurrency/EXERCISES.md) |
| 4 — Design Patterns | [design-patterns/EXERCISES.md](../design-patterns/EXERCISES.md) |
| 5/8 — Spring & Caching | [spring/EXERCISES.md](../spring/EXERCISES.md) |
| 6 — Security | [security/EXERCISES.md](../security/EXERCISES.md) |
| 7 — Database | [database/EXERCISES.md](../database/EXERCISES.md) |
| 9 — Angular | [angular/EXERCISES.md](../angular/EXERCISES.md) |
| 10 — Testing | [testing/EXERCISES.md](../testing/EXERCISES.md) |
| 11 — JVM Internals | [java-advanced/jvm-internals/EXERCISES.md](../java-advanced/jvm-internals/EXERCISES.md) |
| 12 — AWS | [aws/EXERCISES.md](../aws/EXERCISES.md) |
| 13 — System Design | [system-design/EXERCISES.md](../system-design/EXERCISES.md) |

Every module's exercises end in a scenario-style question that's harder and more open-ended than the rest — treat those as the real bar, not the warm-up ones before them.

## A few exercises that only make sense once you've done more than one module

These aren't duplicated into any single module's `EXERCISES.md` because they require code/concepts from more than one — do them after finishing the modules they span:

1. **Close the concurrency loop end-to-end.** `java-basics/EXERCISES.md` (exercise 5) asks you to attempt a `ConcurrentHashMap.compute()` fix for `Inventory.reserve()`'s race condition yourself. `java-advanced/concurrency/` then gives four real implementations (`ConcurrentInventory`, `SynchronizedInventory`, `StripedLockInventory`, plus the buggy baseline) with a shared stress-test harness. Compare your own attempt against all four — where does it fall on the throughput-vs-simplicity spectrum they establish?
2. **Reconcile Angular's assumed API contract against the real one.** `angular/README.md` states the REST contract it assumed while being built concurrently with (and without reading) `spring/`. Now that both exist, diff them: does `spring/`'s actual `OrderController` match what `angular/`'s services assume (paths, request/response shapes, error codes)? Fix any drift in the Angular services and document what you found — this is a realistic version of a frontend/backend contract mismatch you'll hit on real teams.
3. **Trace one request through every layer.** Pick a single action — "customer places an order for 2 items, one of which is out of stock" — and trace it through `angular/` (form submission) → `security/` (JWT validation, RBAC check) → `spring/` (controller → service → repository, `InsufficientStockException` thrown) → `database/` (the transaction that gets rolled back) → back up to `angular/` (how the 409 response is displayed). Write the trace down as a sequence diagram. This is exactly the kind of "walk me through what happens when a user clicks X" question senior interviews ask.
4. **Map the monolith onto `system-design/`'s microservices split.** `system-design/README.md` proposes bounded contexts (Ordering/Inventory/Payments/Shipping/Customer) and recommends starting as a modular monolith. Go back through `spring/`'s actual package structure (`entity/`, `repository/`, `service/`, `controller/`) and annotate which classes would move to which future service if/when a split happened — and which ones (if any) don't cleanly belong to exactly one context, which is itself a useful signal about where your bounded-context boundaries need rework.

See the root [README.md](../README.md) for the full module roadmap.
