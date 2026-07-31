# java-advanced/

Three modules, each in its own subfolder, all extending the same Order/Inventory domain from [java-basics/](../java-basics/) (imported by class, never copied):

| Subfolder | Module | Covers |
|---|---|---|
| [file-io/](file-io/) | Module 2 — File System APIs | `java.io`, `java.nio`, `Path`/`Files`, `WatchService`, ZIP/CSV/JSON, serialization, file locks, large-file streaming — bulk order import, inventory snapshot export |
| [concurrency/](concurrency/) | Module 3 — Multithreading & Concurrency | Executors, CompletableFuture, locks, atomics, concurrent collections, Virtual Threads, deadlocks — fixes the race condition deliberately left open in `java-basics`' `Inventory.reserve()` |
| [jvm-internals/](jvm-internals/) | Module 11 — JVM Internals | Memory model, GC, class loading, JIT, JFR/JMC, JMH — applied to reasoning about this system's runtime behavior under load |

Each subfolder is a complete, self-contained module in the same shape as `java-basics/`: `README.md` (theory), `diagrams/`, `src/`, `EXPLANATION.md`, `EXERCISES.md`, `INTERVIEW.md`. Start with `file-io/`, then `concurrency/` (it directly closes a loop `file-io` and `java-basics` leave open), then `jvm-internals/` last since it references behavior from both.

See the root [README.md](../README.md) for the full curriculum roadmap.
