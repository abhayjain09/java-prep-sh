# Module 11 — JVM Internals

**Domain used throughout:** the same Order/Inventory system as every other
module — `Customer`, `Product`, `Order`, `OrderLine`, `Inventory`,
`OrderService` (all in `java-basics/`, referenced here by import, never
redefined). Where Module 1 asked "does this code work?", this module asks
"what does running this code actually cost the JVM, and how would I find
out?" Every concept below is anchored to a concrete question about this
same service: *if `OrderService.placeOrder` handles a high volume of orders
per second, what does that do to GC pause times, and how would I diagnose
it if support paged me at 2am saying "placeOrder is slow under load"?*

Companion files:
- [diagrams/heap-regions.md](diagrams/heap-regions.md) — heap/stack/metaspace/off-heap layout
- [diagrams/gc-cycle-flow.md](diagrams/gc-cycle-flow.md) — minor GC → promotion → major/full GC flow
- [diagrams/classloader-hierarchy.md](diagrams/classloader-hierarchy.md) — classloader delegation model
- [diagrams/jit-tiered-compilation.md](diagrams/jit-tiered-compilation.md) — interpreter → C1 → C2 → deopt
- [src/](src/) — illustrative code (allocation patterns, escape analysis, classloading, naive benchmarking, a JMH skeleton, lock contention)
- [EXPLANATION.md](EXPLANATION.md) — line-by-line walkthrough of every file in `src/`
- [EXERCISES.md](EXERCISES.md) — hands-on exercises, run on your own machine (this sandbox has no `java`)
- [INTERVIEW.md](INTERVIEW.md) — beginner/intermediate/senior/scenario interview questions with ideal answers

**A note on what "run this yourself" means for this module.** Every other
module's code runs and prints an obviously-correct result. This module is
different in kind: GC behavior, JIT compilation decisions, and profiler
output are runtime, JVM-version-, and hardware-dependent facts, not
deterministic program output. This sandbox has no `java`/`javac`, no
Maven/Gradle, and no ability to run JFR recordings or JMH benchmarks — so
every piece of "captured output" in this module (GC logs, benchmark
numbers) is explicitly labeled **ILLUSTRATIVE EXAMPLE OUTPUT**, meaning
"this is the shape and fields you'd see," never "this is a real capture."
Where behavior *is* deterministic and JVM-spec-guaranteed (e.g.
`ClassNotFoundException` vs. `NoClassDefFoundError` — see section 3 —
`ExceptionInInitializerError`), the code is written so you can compile and
run it yourself and see the real thing, not an approximation.

---

## 1. JVM Memory Model: Heap, Stack, Metaspace, Off-Heap

### What it is
The JVM divides the memory it manages (and memory it merely *tracks*, in
the off-heap case) into distinct regions with different lifetimes, sizing
rules, and collection strategies:
- **Heap** — where every object (`new Order(...)`, `new OrderLine(...)`,
  arrays) lives. Subdivided into a **young generation** (**Eden** plus two
  **survivor spaces**, `S0`/`S1`) and an **old/tenured generation**. Sized
  by `-Xms` (initial) / `-Xmx` (max). The *only* region garbage collectors
  operate on.
- **Stack** — one per thread, holding call frames (local variables,
  operand stack, return addresses). Sized by `-Xss`. Never shared between
  threads, never touched by GC (it's reclaimed automatically as frames pop
  off when methods return).
- **Metaspace** — native memory (outside the heap) holding class metadata:
  bytecode, the constant pool, field/method layout, one copy per loaded
  class no matter how many instances exist. Replaced **PermGen** in Java
  8. Bounded by `-XX:MaxMetaspaceSize` (unbounded by default, up to
  available native memory).
- **Off-heap / direct memory** — native memory allocated via
  `ByteBuffer.allocateDirect(...)` (see Module 2's `java.nio` coverage),
  used so I/O syscalls can read/write straight from native memory without
  an extra copy through a heap `byte[]`. Bounded by
  `-XX:MaxDirectMemorySize`. Freed only when the *wrapping* `ByteBuffer`
  Java object is garbage collected and its `Cleaner` runs.

See [diagrams/heap-regions.md](diagrams/heap-regions.md) for the full
picture, and `src/.../MemoryRegionsDemo.java` for a runnable line-by-line
narration of which region owns each value in a small Order/Inventory setup.

### Why introduced
Uniform, single-region memory management doesn't scale to the actual
lifetime distribution real programs exhibit: most objects (a per-request
`Order`) die within microseconds, while a small minority (a shared
`Inventory` instance, a connection pool) live for the entire process
lifetime. Splitting the heap by *expected lifetime* (young vs. old) lets
the collector use a cheap, frequent algorithm for the common case (mostly
garbage, collect it fast) and a more expensive, infrequent algorithm for
the rare case (mostly still alive, don't bother re-scanning it constantly).
Metaspace's split from the heap (Java 8) solved a *different* problem:
class metadata has its own, very different growth pattern (grows with
number of *classes loaded*, not number of *objects created*) that doesn't
belong sized alongside object storage.

### Problem it solves
Before generational collection, a garbage collector had to scan the entire
live object graph on every collection — expensive, and wasteful precisely
because most of what it would scan is short-lived garbage by the time the
scan runs. Generational GC (see section 2) exploits the **generational
hypothesis** — most objects die young — directly enabled by having a
distinct, small, fast-to-scan young generation. Metaspace, specifically,
solved PermGen's fixed-size, heap-adjacent sizing problem: PermGen had to
be sized with `-XX:MaxPermSize` up front, and applications that loaded
classes dynamically at scale (heavy use of dynamic proxies, OSGi, app
servers hot-redeploying WARs) would routinely hit `OutOfMemoryError:
PermGen space` even when the object heap itself had plenty of room.
Metaspace grows dynamically from native memory instead, removing that
specific fixed-size failure mode (at the cost of a new one — see Common
mistakes below).

### When to use / when NOT to use (i.e., what to actually tune)
- Size `-Xmx` based on measured working-set size plus headroom for GC to
  have room to work efficiently (a heap that's *always* near-full forces
  the collector to run constantly) — not by guessing or copy-pasting a
  number from another service with a different allocation profile.
- Size `-Xss` per-thread stack size *down* only if you're intentionally
  running very large numbers of threads and hitting native memory limits
  (each thread's stack is reserved address space); the default (512KB–1MB
  depending on platform) is fine for the vast majority of services and
  should not be tuned reflexively.
- Set `-XX:MaxMetaspaceSize` explicitly in production once you have a
  measured baseline — leaving it unbounded means a classloader leak (see
  Common mistakes) degrades into unbounded *native* memory growth that can
  take down the whole host, not just the JVM, before anyone notices.
- Set `-XX:MaxDirectMemorySize` explicitly whenever direct buffers are used
  deliberately (Module 2's NIO-based bulk order import/export is exactly
  this case) — the default is derived from `-Xmx` but is easy to exhaust
  silently since it doesn't show up in ordinary heap monitoring.

### Trade-offs & performance implications
- A larger young generation means fewer, but each individually more
  work, minor GCs (more live data to scan/copy per collection) — there's
  no free lunch; young-gen sizing is a throughput/pause-time trade curve,
  not a "bigger is always better" knob (see section 2's collector
  discussion for how G1/ZGC try to make this trade-off adaptive instead of
  a fixed flag).
- Off-heap memory trades a small amount of complexity (manual-feeling
  lifecycle, no automatic bounds checking against `-Xmx`) for avoiding a
  copy on I/O paths — worth it for genuinely I/O-heavy, high-throughput
  code (bulk file import/export), actively harmful complexity for ordinary
  request/response object graphs, which should just be on the regular
  heap.
- Metaspace being "unbounded by default" is a double-edged trade: it
  removes PermGen's fixed-size OOM but replaces it with "silently consumes
  all native memory on the host" if something (frequently: a classloader
  leak from dynamically generated classes/proxies never being unloaded) is
  wrong — unbounded is not the same as "safe to ignore."

### Enterprise examples
- A Spring Boot service with a per-request `Order`-shaped DTO graph is the
  textbook young-gen-dominant workload: almost every allocation dies
  before the HTTP response is written, which is exactly why generational
  GC (section 2) works so well for typical web services.
- A bulk nightly reconciliation job that reads a multi-GB order export via
  `java.nio` `FileChannel`s and direct `ByteBuffer`s (Module 2) is the
  textbook case for explicitly tuning `-XX:MaxDirectMemorySize` and
  reusing a small pool of direct buffers instead of allocating fresh ones
  per chunk.
- An application server that dynamically generates proxy classes per
  deployed module (common in older Java EE / OSGi platforms) is the
  textbook PermGen-then-Metaspace horror story that motivated Java 8's
  metaspace redesign in the first place.

### Common mistakes
- Treating "Metaspace is unbounded by default" as "Metaspace can't leak" —
  it absolutely can (classloader leaks: a `ClassLoader` that should have
  been garbage collected after a redeploy is kept alive by a lingering
  reference, keeping every class it loaded, and their metadata, alive
  forever).
- Confusing a reference (on the stack, or a field on the heap) with the
  object it points to (always on the heap) — this shows up constantly in
  interview answers as "the `Order` variable is on the stack," which is
  only true of the *reference*; the `Order` object itself is on the heap.
- Sizing `-Xmx` to "as much as the box has" without leaving headroom for
  the OS, other processes, and native/off-heap/thread-stack memory —
  a JVM configured this way is one direct-buffer-heavy batch job away from
  the OS OOM-killer terminating the process outright, which produces a far
  less diagnosable failure than a clean `OutOfMemoryError`.

---

## 2. Garbage Collection

### What it is
Automatic reclamation of heap memory occupied by objects no longer
reachable from a GC root (a thread's stack, a static field, a JNI
reference). The **generational hypothesis** — most objects die young; the
few that don't tend to live a long time — is the foundational assumption
behind splitting the heap into young/old generations (section 1) and
behind every modern collector's design.

- **Minor GC**: collects only the young generation. Frequent, normally
  fast (often single-digit milliseconds), because most of what it scans
  turns out to be garbage.
- **Major GC / Full GC**: collects the old generation (major) or the
  entire heap including metaspace (full). Much rarer, much more expensive
  per event, because old-gen objects were promoted *because* they survived
  before — the "assume most of it's dead" shortcut doesn't hold there.

See [diagrams/gc-cycle-flow.md](diagrams/gc-cycle-flow.md) for the full
minor-GC → promotion → major/full-GC cycle, applied to a high-volume
`OrderService.placeOrder` workload.

### Why introduced
Manual memory management (`malloc`/`free`-style, or C++'s destructors) puts
correctness of every deallocation on the programmer — a single missed
`free` is a leak, a single early `free` is a use-after-free / dangling
pointer, both classes of bug that plagued systems software for decades and
are frequently exploitable security vulnerabilities. GC trades a
(tunable, and for most business logic, negligible) runtime cost for
eliminating that entire bug class by construction — an `Order` object is
reclaimed automatically once nothing can reach it anymore, full stop, no
matter how many places briefly held a reference to it.

### Problem it solves
Without GC, `OrderService.placeOrder`'s current design — allocate an
`Order`, attach `OrderLine`s, hand it back to the caller — would require
every caller, transitively, to know exactly when it's safe to free that
`Order` and every object it references, including objects shared with
other parts of the system (the `Product` referenced by an `OrderLine` is
also referenced by the catalog). GC removes that entire category of
bookkeeping from application code.

### Collector comparison — when each is chosen

| Collector | Pause behavior | Best for | Flag |
|---|---|---|---|
| **Serial** | Single-threaded, stop-the-world for both young and old | Small heaps (client apps, containers with 1 CPU / <~100MB heap) where a dedicated GC thread isn't worth it | `-XX:+UseSerialGC` |
| **Parallel** ("throughput collector") | Multi-threaded, stop-the-world for both young and old; was the **default before Java 9** | Batch/offline jobs (nightly reconciliation, bulk order export) that care about total throughput and can tolerate longer individual pauses | `-XX:+UseParallelGC` |
| **G1** ("Garbage First") | Region-based heap; concurrent marking, mostly-concurrent old-gen work, targets a configurable pause-time **goal** rather than a fixed algorithm shape | General-purpose default for most services — **the default collector since Java 9** — balances throughput and latency without hand-tuning | `-XX:+UseG1GC` (implicit by default) |
| **ZGC** | Concurrent, colored-pointer/load-barrier based; pauses typically sub-millisecond to a few ms **regardless of heap size** (scales to multi-TB heaps) | Latency-critical services with large heaps and strict tail-latency SLAs (trading systems, real-time bidding) where even G1's occasional longer pause is unacceptable | `-XX:+UseZGC` |
| **Shenandoah** | Similar goal to ZGC (low, mostly heap-size-independent pauses) via concurrent compaction (Brooks pointers); developed by Red Hat | Same latency-sensitive niche as ZGC — choice between the two is often about vendor/JDK distribution availability and specific workload benchmarking rather than a clean-cut rule | `-XX:+UseShenandoahGC` |

The underlying trade-off every row above sits on is **throughput vs.
latency**: Parallel maximizes total work done per unit CPU time at the
cost of occasional long pauses; ZGC/Shenandoah minimize the *worst* pause
a request could ever see, at some cost to raw throughput and CPU overhead
from doing more work concurrently. G1 sits deliberately in the middle and
is the right *default* starting point for the vast majority of services,
including a typical `OrderService`-shaped web application — reach for
ZGC/Shenandoah only once you have measured evidence that G1's pause-time
goal isn't good enough for your actual SLA, not preemptively.

### GC tuning flags overview
- `-Xms<size>` / `-Xmx<size>` — initial / maximum heap size. Setting them
  equal (`-Xms4g -Xmx4g`) avoids pause-inducing heap *resizing* at runtime
  in exchange for reserving the full amount up front — the standard
  production recommendation once you know your working set.
- `-XX:+UseG1GC` / `-XX:+UseParallelGC` / `-XX:+UseZGC` /
  `-XX:+UseShenandoahGC` — collector selection (mutually exclusive).
- `-XX:MaxGCPauseMillis=<ms>` — a **goal**, not a guarantee, that G1 uses
  to size regions and decide how much old-gen work to fold into each young
  collection; setting it unrealistically low forces G1 to do more frequent,
  smaller collections chasing a target it can't actually hit cleanly.
- `-XX:NewRatio=<n>` / `-XX:SurvivorRatio=<n>` — relative sizing of young
  vs. old generation, and Eden vs. survivor spaces within young gen.
- `-XX:MaxMetaspaceSize=<size>` — bound metaspace (see section 1).
- `-Xlog:gc*:file=gc.log:time,uptime,level,tags` — unified JVM logging
  (Java 9+) for GC events; the pre-9 equivalent was
  `-XX:+PrintGCDetails -XX:+PrintGCDateStamps`.
- `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<path>` — capture a
  heap dump automatically on OOM, essential for root-causing a leak after
  the fact rather than trying to reproduce it live.

### How to read a GC log

```
ILLUSTRATIVE EXAMPLE OUTPUT ONLY — hand-written to show real G1 unified-log
field shapes and vocabulary; NOT a captured log from an actual run (this
sandbox has no `java` binary to produce one).

[2026-07-31T10:15:32.101+0000][0.512s][info][gc,start    ] GC(12) Pause Young (Normal) (G1 Evacuation Pause)
[2026-07-31T10:15:32.101+0000][0.512s][info][gc,heap     ] GC(12) Eden regions: 45->0(46)
[2026-07-31T10:15:32.101+0000][0.512s][info][gc,heap     ] GC(12) Survivor regions: 6->5(7)
[2026-07-31T10:15:32.101+0000][0.512s][info][gc,heap     ] GC(12) Old regions: 12->14(30)
[2026-07-31T10:15:32.118+0000][0.529s][info][gc          ] GC(12) Pause Young (Normal) (G1 Evacuation Pause) 92M->48M(128M) 17.482ms
```

Field-by-field:
- **`GC(12)`** — the 13th GC event (0-indexed) since JVM start; lets you
  correlate the multi-line "start"/"heap"/summary lines above as one event.
- **`Pause Young (Normal) (G1 Evacuation Pause)`** — the GC *type* and
  *cause*: a normal young-generation collection (not a mixed collection,
  not a full GC).
- **`Eden regions: 45->0(46)`** — Eden held 45 of its 46-region capacity
  before this pause, and 0 after — i.e., Eden was fully evacuated (every
  live object copied out to survivor/old, everything else reclaimed).
- **`Survivor regions: 6->5(7)`** — survivor usage went from 6 to 5
  regions (some survivor-space objects were promoted to old gen, or aged
  out and died, netting a decrease) out of a 7-region survivor capacity.
- **`Old regions: 12->14(30)`** — old gen grew from 12 to 14 regions (out
  of 30 capacity) — this is the **promotion** in action: objects that
  survived enough young collections moved here (see
  `diagrams/gc-cycle-flow.md`).
- **`92M->48M(128M) 17.482ms`** — the headline summary: total heap usage
  went from 92MB to 48MB (out of a 128MB current capacity), and the pause
  itself took 17.482 milliseconds. **This is the number an SRE dashboard
  usually graphs** — pause duration over time — and a sustained upward
  trend in either pause duration or pause *frequency* for the same traffic
  volume is the standard early warning sign of a memory leak (see section
  1's classloader-leak example, or an `OrderService` accidentally
  accumulating an unbounded `List<Order>` audit trail in memory).

A **Full GC** log line looks similar but is typically labeled `Pause Full
(...)`, takes far longer (often hundreds of ms to seconds on a large old
gen), and — critically — its *frequency increasing over time* on stable
traffic is the single most reliable GC-log signature of a genuine memory
leak, as opposed to normal minor-GC churn from ordinary request traffic.

### When to use / when NOT to tune GC manually
- Start with the default (G1) and default flags for any new service; only
  add explicit tuning once you have a *measured* problem (a specific
  latency SLA being missed, correlated with GC pause data from logs/JFR) —
  tuning against a hypothetical problem, or copying another team's flags
  without understanding their workload, routinely makes things worse.
- Do reach for explicit `-Xms`/`-Xmx` equality and a `MaxMetaspaceSize`
  bound as a baseline production hygiene measure even without a specific
  problem — these are cheap, well-understood safety nets, unlike
  algorithm-specific tuning flags.

### Trade-offs & performance implications
- Every stop-the-world pause, however short, is dead time from the
  application's perspective — for a `placeOrder` call unlucky enough to be
  in flight when one starts, its *own* latency includes that pause,
  invisible to naive method-level timing that doesn't account for GC.
- Concurrent collectors (G1's concurrent marking, ZGC, Shenandoah) don't
  eliminate GC *cost*, they move most of it off the stop-the-world critical
  path and onto background threads competing for the same CPUs your
  request-handling threads want — under sustained high CPU utilization,
  "low pause time" collectors can still slow down overall throughput.

### Enterprise examples
- A high-frequency trading or market-data platform choosing ZGC
  specifically because a single 200ms G1 pause landing on the wrong
  request is a measurable, costly tail-latency SLA breach, even though G1
  would give better *average* throughput for the same hardware.
- A nightly batch reconciliation job (read every order from yesterday,
  cross-check against a ledger) explicitly choosing Parallel GC because it
  runs unattended overnight with no latency SLA at all — total wall-clock
  completion time is the only thing that matters, and Parallel maximizes
  throughput per CPU-second spent on GC.

### Common mistakes
- Reading "92M->48M" as "44MB of objects died" without noticing the
  `(128M)` — the parenthesized number is *current total capacity*, which
  can itself change between events (G1 resizes regions), and conflating
  "heap usage after GC" with "the working set" ignores objects currently
  in Eden that simply weren't scanned in that particular pause.
- Tuning `-XX:MaxGCPauseMillis` aggressively low as a first response to a
  latency complaint without checking (via JFR — section 5) whether GC
  pauses are even the actual cause of the slowness, versus, say, lock
  contention (see `LockContentionUnderLoadDemo.java`) or a genuinely slow
  downstream call.
- Assuming a bigger heap always helps — a larger heap can mean each Full
  GC, when it does happen, takes proportionally longer, trading pause
  *frequency* for pause *severity* if the underlying allocation/leak
  behavior isn't actually fixed.

---

## 3. Class Loading

### What it is
The mechanism by which the JVM turns a `.class` file's bytes into a usable
`Class` object at runtime, structured as a **hierarchy of classloaders**
that **delegate** load requests to their parent before trying themselves:
- **Bootstrap classloader** — native, part of the JVM itself, loads
  `java.base` (`java.lang.*`, `java.util.*`, ...). Has no Java-level
  object; `SomeCoreClass.class.getClassLoader()` returns `null`.
- **Platform classloader** (named "Extension"/`ext` before Java 9) — loads
  other JDK platform modules.
- **Application/System classloader** — loads everything on your
  `-classpath`, including `com.interviewprep.orders.domain.Order` and this
  module's own classes.
- Optionally, **custom classloaders** beneath Application (servlet
  container per-WAR isolation, plugin systems, hot-reload frameworks).

See [diagrams/classloader-hierarchy.md](diagrams/classloader-hierarchy.md)
for the full picture and `src/.../ClassLoaderHierarchyDemo.java` for a
runnable walk of the actual chain plus both exception types below,
reproduced deterministically.

### Why introduced
A hierarchical, delegating model gives two things a flat "just search the
classpath" model can't: (1) **integrity** — application code can never
accidentally (or maliciously) shadow a core class like `java.lang.String`,
because Bootstrap is always consulted first for anything the delegation
walk reaches it for; and (2) **isolation** — a servlet container can load
two different web apps' conflicting versions of the same library
independently, each with its own classloader, without one clobbering the
other's classes.

### Problem it solves
Without delegation, "which version of this class actually got loaded" in
a system with multiple potential sources (JDK core classes, shared
platform libraries, per-application code, dynamically loaded plugins)
would be an ambiguous, order-of-classpath-entry-dependent question — a
notorious source of "works on my machine" bugs from classpath ordering
differences. Parent-first delegation makes the *answer* to "which
classloader is authoritative for `java.lang.String`" a structural
guarantee, not a coincidence of classpath ordering.

### `ClassNotFoundException` vs. `NoClassDefFoundError` — the practical difference
- **`ClassNotFoundException`** (checked, extends `Exception`): thrown when
  code makes an **explicit, by-name** load request (`Class.forName(...)`,
  `classLoader.loadClass(...)`) and every classloader consulted in the
  delegation walk fails to find it. **Root cause: the class genuinely isn't
  on the classpath anywhere.** Classic real-world trigger: a JDBC driver
  referenced by string (`Class.forName("oracle.jdbc.OracleDriver")`) when
  the driver JAR was never added to the deployment.
- **`NoClassDefFoundError`** (unchecked, extends `LinkageError`): thrown
  when a class **was** available and loadable at some point (often
  successfully compiled against, and sometimes even successfully loaded
  once already at runtime) but a **later, implicit** reference to it fails
  — either because (a) the class file has since disappeared from the
  runtime classpath (a packaging/deploy mismatch — compiled against one
  set of JARs, run against another), or (b) the class's own static
  initializer (`<clinit>`) already threw once, permanently marking the
  class "erroneous" per the JLS — every subsequent reference throws
  `NoClassDefFoundError` instead of retrying `<clinit>` (see
  `ClassLoaderHierarchyDemo.java` for a deterministic, runnable
  reproduction of exactly this sequence).
- **Practical difference that matters in an interview and in an incident:**
  `ClassNotFoundException` is a classpath/dependency problem you fix by
  adding the missing artifact. `NoClassDefFoundError` is a signal to go
  looking for an *earlier* failure — either a deploy/classpath mismatch, or
  (very commonly, and often confusingly) a static-initializer exception
  that was logged once, possibly during an unrelated earlier request, and
  is now manifesting as an unrelated-looking error on every subsequent
  touch of that class.

### Java 9+ module system's interaction with classloading
The module system (JPMS, `module-info.java`) adds a **module graph**
layered *on top of* the classloader delegation model described above, not
a replacement for it — modules still get loaded by the same three-tier
classloader hierarchy (each of the JDK's built-in modules is associated
with a specific classloader). What the module system *adds*: explicit
`requires`/`exports` declarations that enforce **strong encapsulation** —
a module's internal (non-exported) packages are inaccessible to other
modules even via reflection, by default, closing off the "just reach in
with `setAccessible(true)`" escape hatch that reflection-heavy frameworks
(older Hibernate/Jackson versions, some ORMs) relied on against JDK
internals. This is *the* practical reason "does this reflection-heavy
library still work on Java 17?" became a real migration question — code
that used to reflectively access `sun.*`/JDK-internal packages now needs
an explicit `--add-opens` JVM flag, or the library needs an update that
avoids the internal access entirely.

### When to use / when NOT to use custom classloaders
- Use a custom classloader when genuine runtime isolation is required
  (a plugin architecture where plugins may ship conflicting library
  versions; a servlet container isolating deployed WARs from each other).
- Avoid reaching for a custom classloader to solve an ordinary dependency
  version conflict in a single application — that's what build-tool
  dependency management (Maven's dependency mediation, explicit exclusions)
  exists for; a hand-rolled classloader is a much heavier, harder-to-debug
  hammer for that problem.

### Trade-offs & performance implications
- Class loading and initialization (`<clinit>` running) happen exactly
  once per classloader per class and are then cached — negligible ongoing
  cost, but a large number of classes loaded at startup (common in
  dependency-injection-heavy frameworks doing classpath scanning) is a
  real, measurable contributor to JVM cold-start time, which is why
  frameworks increasingly offer ahead-of-time / closed-world alternatives
  (GraalVM native image being the extreme end of that spectrum).
- Multiple classloaders each loading "the same" class (by fully-qualified
  name) produces genuinely **different, mutually-incompatible `Class`
  objects** at runtime — an object created by one copy cannot be cast to
  the other copy's type even though the source code is identical. This is
  the classic cause of a baffling `ClassCastException: com.foo.Bar cannot
  be cast to com.foo.Bar` (same-looking name!) in app-server environments
  with classloader isolation.

### Enterprise examples
- Application servers (Tomcat, older JBoss/WebLogic) giving each deployed
  WAR its own classloader specifically so that App A's Jackson 2.10 and
  App B's Jackson 2.17 can coexist on the same JVM without conflict.
- A Spring Boot fat JAR using its own layered classloader
  (`LaunchedURLClassLoader`) to load nested JARs from inside a single
  executable JAR file — a practical, everyday application of a custom
  classloader most developers never think about because it "just works."

### Common mistakes
- Catching `ClassNotFoundException` and assuming the fix is always "add
  the missing dependency" without checking whether it's actually a
  classloader *isolation* problem (the class exists, just not visible from
  the classloader making the request — common in app-server/OSGi
  environments).
- Treating every `NoClassDefFoundError` as a "just redeploy/clean-build"
  problem without checking the logs for an earlier
  `ExceptionInInitializerError` for that exact class — the real root cause
  is usually there, potentially much earlier in the logs than the
  `NoClassDefFoundError` itself.
- Assuming Java 9+ module boundaries only matter if you personally wrote
  a `module-info.java` — plenty of *unnamed-module* (classpath-based)
  applications still get bitten by strong encapsulation the first time
  they upgrade the JDK and a transitive dependency's reflective access to
  a JDK-internal package starts failing.

---

## 4. JIT Compilation

### What it is
The **interpreter** executes bytecode directly the moment a class loads —
slow per-invocation, but instantly available, and it doubles as a
**profiler**, recording which branches are actually taken and what
concrete types show up at each call site. Once a method is called (or a
loop iterates) often enough, the JVM compiles it to native machine code
using one of two just-in-time compilers:
- **C1 ("client" compiler)** — compiles fast, with modest optimization.
  Optimizes for *low compile latency*, not peak runtime speed.
- **C2 ("server" compiler)** — compiles slowly but aggressively: inlining,
  loop unrolling, **escape analysis**, and speculative optimizations based
  on the profiling data the interpreter/C1 gathered. Optimizes for *peak
  throughput* once a method is hot enough to be worth the compile cost.

**Tiered compilation** (`-XX:+TieredCompilation`, default since Java 8) is
the JVM using *both*: a method climbs interpreter → C1 (multiple internal
tiers, still profiling) → C2, recompiling only when the evidence justifies
the next tier's cost. See
[diagrams/jit-tiered-compilation.md](diagrams/jit-tiered-compilation.md).

### Why introduced
A pure interpreter is portable and starts instantly but is far slower,
per-call, than compiled native code for hot loops — unacceptable for
long-running server workloads. A pure ahead-of-time compiler (compile
everything to native code before running) can't use *runtime* information
(which branch is actually hot, which concrete type actually shows up at a
call site) that turns out to matter enormously for optimization quality —
information a static compiler processing bytecode alone simply doesn't
have. JIT compilation gets both: instant startup via the interpreter, and
eventually *better-than-static-compilation* optimization for hot code,
because it optimizes based on what the program is *actually doing*, not
just what it could theoretically do.

### Problem it solves
Compiling every method eagerly at startup, with full C2-level
optimization, would make JVM startup unacceptably slow for the vast
majority of methods that run once or a handful of times and never again
(most of a typical request's call graph). Tiered compilation targets
compilation effort at exactly the methods where it pays off — the hot 10%
doing 90% of the work — while leaving everything else cheap to start and
run interpreted or lightly (C1) compiled.

### Warm-up, and why naive microbenchmarks get this wrong
A method must be invoked (or a loop must iterate) many times — typically
low thousands, tier-dependent — before the JIT compiles it at all, and
more before C2's aggressive optimizations kick in. A benchmark that
measures a **single pass** through a loop is, for most of that pass,
measuring **interpreter and C1 speed**, not the C2-optimized steady-state
speed the same code would have in a long-running production process. This
is *the* reason `System.currentTimeMillis()`-around-a-loop benchmarks are
misleading (see `NaiveMicrobenchmarkPitfalls.java` and section 6) — and
it's compounded by **dead code elimination**: if the JIT can prove a
loop's result is never observably used, it can legally delete the
computation the benchmark meant to measure, in addition to the warm-up
skew. JMH (section 6) exists specifically to control for both.

### Inlining, and why it matters
Inlining replaces a call site with the callee method's body directly,
which (a) removes call/return overhead, and (b) — the bigger effect —
**opens the door to further optimization across what used to be a method
boundary**: dead code elimination, redundant computation removal, and
escape analysis all become possible across an inlined call in ways they
cannot across an opaque method call. A hot `OrderLine.lineTotal()` called
from inside `Order.totalAmount()`'s stream pipeline is exactly the kind of
small, hot method the JIT will aggressively try to inline — once inlined,
the surrounding loop and the inlined multiplication can be optimized
*together*. Inlining decisions are governed by method size thresholds
(`-XX:MaxInlineSize`, `-XX:FreqInlineSize`) and are actively *hindered* by
**megamorphic call sites** — an interface call site that has seen many
different concrete implementations can't be speculatively inlined the way
a call site that's only ever seen one (monomorphic) or two (bimorphic)
implementations can.

### Escape analysis and stack allocation of non-escaping objects
C2 analyzes whether an object created inside a method can ever be observed
*outside* that method or thread — stored in a field, returned, handed to
another thread. If it provably cannot **escape**, C2 may apply **scalar
replacement**: decompose the object into its individual fields, keep them
in registers/on the stack, and skip the heap allocation entirely — meaning
no GC involvement whatsoever for that object. Relevant to this domain: a
tight loop computing running totals by constructing a temporary
`OrderLine`, calling `.lineTotal()`, and discarding it (see
`EscapeAnalysisDemo.sumLineTotalsNonEscaping`) is a *plausible candidate*
for this optimization once hot — versus a method that builds and *returns*
a `List<OrderLine>` (`EscapeAnalysisDemo.buildEscapingLines`), where every
constructed `OrderLine` provably escapes and scalar replacement is never
legal. This is genuinely important for interviews (it explains why a
"wasteful-looking" hot loop can, after warm-up, allocate far less than a
cold-code allocation profile would suggest) but is also genuinely
unverifiable by reading source code alone — confirming it requires either
diagnostic JIT flags on a debug JVM build or a JFR allocation-rate
comparison (see the note at the bottom of `EscapeAnalysisDemo.java`).

### When to use / when NOT to rely on JIT behavior
- Write normal, idiomatic code (small methods, monomorphic call sites
  where practical) and trust tiered compilation to optimize the hot paths
  — don't hand-inline methods or avoid polymorphism preemptively in
  business logic "for performance" without profiling evidence it matters.
- Do account for warm-up explicitly whenever you're timing anything: a
  latency SLA measured from cold-start (first N requests after a deploy)
  is a legitimately different number from steady-state latency, and both
  matter for different reasons (deploy-time user impact vs. sustained
  capacity planning) — conflating them is a common production-metrics
  mistake.

### Trade-offs & performance implications
- JIT compilation itself consumes CPU on background compiler threads —
  on a very CPU-constrained container (a common cost-optimization move
  that can backfire), aggressive tiered compilation competing for the same
  limited CPU as request-handling threads can measurably hurt latency
  during a traffic ramp-up, which is why some latency-sensitive services
  explicitly warm up critical code paths with synthetic traffic *before*
  accepting real traffic after a deploy.
- Deoptimization (falling back to the interpreter when a C2 speculative
  assumption turns out wrong — see the diagram) is rare in steady state
  but can cause a visible, momentary latency spike right after a new class
  is loaded that invalidates an existing monomorphic call-site assumption
  elsewhere in the running system — a subtle, hard-to-attribute cause of
  occasional latency spikes in systems that load classes dynamically at
  runtime.

### Enterprise examples
- Every JVM-based trading or bidding system's "pre-warming" step before
  taking production traffic after a deploy — deliberately driving synthetic
  requests through the hot code paths first so C2 has already compiled
  them before real, latency-measured traffic arrives.
- A performance regression investigation that turns out to be caused by a
  newly-added third implementation of a previously-bimorphic interface
  (e.g. a third `PaymentMethod` implementation added to a payment
  processing hot path), pushing a call site from optimizable
  (mono/bimorphic) to megamorphic and measurably slowing down a method
  that hadn't otherwise changed at all.

### Common mistakes
- Benchmarking with a single, short, cold-JVM run and treating the result
  as representative of production steady-state performance (see section 6
  for the full treatment).
- Assuming "the JIT will optimize it away" as a blanket excuse to write
  needlessly wasteful code — escape analysis and inlining are real, but
  they're speculative, method-size-limited, and version/JVM-dependent;
  they are not a substitute for reasoning about actual algorithmic
  complexity or allocation rate in hot paths.
- Reflexively "optimizing" for monomorphism by avoiding polymorphism in
  ordinary business logic that isn't actually hot — most code never
  executes often enough to reach C2 at all, so JIT-level micro-concerns
  are irrelevant there; save this reasoning for code you've profiled as
  genuinely hot.

---

## 5. Profiling with JFR (Java Flight Recorder) and JMC (Java Mission Control)

### What it is
**JFR** is a low-overhead event-recording framework built into the JVM
itself (production-safe, typically low single-digit-percent overhead, safe
to enable in production continuously — unlike older, much heavier
profiling agents). It records structured events — GC pauses, allocations,
lock contention, thread state, I/O — to a `.jfr` file. **JMC** is the GUI
tool for opening and analyzing that file: timelines, hot-method views,
allocation breakdowns by call site, and a dedicated lock-contention view.

### Why introduced
Before JFR, production-safe profiling options were limited: sampling
profilers with real overhead, or turning to heavyweight instrumentation
agents only during an active incident (by which point the problem may have
already resolved itself, or reproducing it on demand may be impossible).
JFR was built by the JVM team with performance as a first-class design
constraint specifically so it can be **left running continuously in
production** (or started on demand via `jcmd` with no restart required),
turning "we'd need to reproduce this in staging with a profiler attached"
into "let me pull the JFR data we already have from when this happened."

### Problem it solves
Diagnosing "the service is slow" **after the fact**, without having to
reproduce the exact conditions (specific load pattern, specific data
shape, specific time of day) that caused it — because the recording was
either already running, or can be started immediately via `jcmd` against
the *already-running, already-affected* process, with no restart and no
loss of the in-progress incident.

### Starting a recording
```bash
# At JVM startup, recording for the process's first 60 seconds:
java -XX:+FlightRecorder \
     -XX:StartFlightRecording=duration=60s,filename=recording.jfr,settings=profile \
     -jar order-service.jar

# Against an ALREADY-RUNNING process (the realistic incident-response case
# -- no restart needed, which matters enormously when restarting would
# lose the very conditions you're trying to capture):
jcmd <pid> JFR.start duration=120s filename=incident.jfr settings=profile

# Check on / stop a recording already in progress:
jcmd <pid> JFR.check
jcmd <pid> JFR.stop name=1 filename=incident.jfr
```
(`-XX:+FlightRecorder` is the flag that historically unlocked JFR on
commercial/older JDK builds; on modern OpenJDK builds JFR is available by
default and this flag is a harmless no-op — included above for
completeness and because you'll still see it in a lot of existing runbooks
and tooling.)

### Commonly useful JFR events
- **`jdk.ObjectAllocationInNewTLAB` / `jdk.ObjectAllocationOutsideTLAB`** —
  allocation profiling: which call sites allocate the most, and how much,
  broken down by object type. The direct way to confirm (or refute) a
  hypothesis like "is `OrderService.placeOrder` allocating more than it
  should per call under load" (see `AllocationPatternsDemo.java` for the
  wasteful-vs-lean pattern this event would surface the difference
  between).
- **`jdk.GarbageCollection` / `jdk.GCPhasePause`** — GC event timeline:
  pause durations, cause, and generation, plotted against the rest of the
  recording's timeline so you can correlate a GC pause with a specific
  slow request window.
- **`jdk.JavaMonitorEnter` / `jdk.ThreadPark`** — lock contention: which
  monitor (`synchronized` block/method) or `java.util.concurrent` lock
  threads are waiting on, for how long, and how often. This is exactly
  what would surface `LockContentionUnderLoadDemo.java`'s
  `PLACE_ORDER_LOCK` as the dominant contended monitor under load.
- **`jdk.ExecutionSample`** — periodic stack sampling across all threads;
  the low-overhead way to answer "where is CPU time actually going" without
  the overhead of full instrumentation — JMC's "Hot Methods" view is built
  directly from this event.
- **`jdk.SocketRead` / `jdk.SocketWrite` / `jdk.FileRead` / `jdk.FileWrite`**
  — I/O timing, useful for distinguishing "the service is slow because of
  CPU/GC/locks" from "the service is slow because a downstream call or disk
  is slow."

### Diagnosing "OrderService.placeOrder is slow under load" — a walkthrough
1. **Capture** a JFR recording spanning the slow window (`jcmd <pid>
   JFR.start ... settings=profile` against the live, currently-affected
   process — no restart, no waiting to reproduce).
2. **GC tab first** — is `placeOrder` slow because of frequent/long GC
   pauses landing during the request? A high allocation rate from
   `Order`/`OrderLine` creation under load (section 1/2) would show up
   here as increased minor-GC frequency; cross-reference against the
   Allocation tab to confirm the source.
3. **Hot Methods (execution samples)** — if GC isn't the story, is CPU
   time actually concentrated inside `Inventory.reserve()`, the streams
   pipeline in `totalSpentByStreams`, or somewhere unexpected (an
   accidental O(n²) pattern, for instance)?
4. **Lock Instances (contention)** — is `placeOrder` (or something it
   calls) blocked waiting on a monitor? This is where a naive
   "just synchronize the whole method" fix for `Inventory`'s documented
   race condition (java-basics `Inventory.java`'s Javadoc) would show up
   as *exactly* the problem — every thread serialized behind one lock
   regardless of which SKU it needed, visible as high summed "Blocked
   Time" on one monitor (see `LockContentionUnderLoadDemo.java`).
5. **Allocation tab** — is a specific call site allocating far more than
   expected per call (an accidental defensive copy in a loop, a needlessly
   large intermediate collection — see `AllocationPatternsDemo.java`'s
   wasteful version for exactly this shape)?
6. **Map finding → fix**: GC-pause-bound → tune young-gen sizing or reduce
   allocation rate; lock-bound → narrow the lock's scope (per-SKU locking
   or `ConcurrentHashMap.compute()`, per java-basics Exercise 5 and
   Module 3); CPU-bound in a specific method → profile and optimize *that*
   method specifically, informed by actual data rather than guesswork.

### When to use / when NOT to enable continuous profiling
- Use JFR's default, low-overhead `settings=default` profile continuously
  in production for every service that matters — the overhead is low
  enough, and the diagnostic value during an actual incident is high
  enough, that "we'll turn on profiling if it happens again" is
  consistently the wrong call.
- Use the heavier `settings=profile` (more detail, slightly more overhead)
  only for a bounded window during active investigation, not as the
  permanent default, unless you've specifically measured its overhead is
  acceptable for your workload.

### Trade-offs & performance implications
- JFR's overhead is deliberately low (frequently cited around 1-2% for the
  default profile) specifically so "always on in production" is a
  realistic default — a meaningfully higher-overhead profiler would force
  the "only attach during an incident" trade-off JFR exists to avoid.
- A `.jfr` recording file can grow large for long durations with detailed
  settings — production use typically bounds recordings with `maxsize=`/
  `maxage=` and a rolling/chunked recording strategy rather than one
  unbounded file.

### Enterprise examples
- A payments platform running JFR continuously in every production pod
  specifically so that a P99 latency spike reported by monitoring can be
  correlated, after the fact, against the JFR data already captured for
  that exact time window — no incident-time profiler attachment required.
- Using JFR's allocation events to justify (with real data, not guesswork)
  a code change that replaced a per-request `ArrayList` rebuild with a
  reused, cleared collection — the kind of change `AllocationPatternsDemo`
  illustrates conceptually.

### Common mistakes
- Reaching for a heavyweight, high-overhead profiling agent as the first
  troubleshooting step for a production incident when JFR (already
  running, or startable via `jcmd` with zero downtime) would answer the
  same question with far less risk of the profiler itself perturbing the
  very behavior being investigated.
- Looking only at CPU/hot-methods data and concluding "nothing's wrong"
  when the actual bottleneck is lock contention or GC pauses — always
  check all of GC, lock, allocation, and CPU views before concluding a
  recording shows nothing actionable.

---

## 6. JMH (Java Microbenchmark Harness)

### What it is
The standard tool for writing *statistically valid* Java microbenchmarks —
built by the same team that builds the JIT compiler specifically because
naive, hand-rolled timing loops are so reliably wrong that "just time it
with `System.currentTimeMillis()`" needed a dedicated, purpose-built
replacement.

### Why naive timing-loop benchmarks are misleading
As covered in section 4, a `System.currentTimeMillis()`-around-a-loop
benchmark (see `NaiveMicrobenchmarkPitfalls.java`) has three independent
problems, any one of which alone can invalidate the result:
1. **JIT warm-up** — early loop iterations run interpreted/C1-compiled,
   not at the C2-optimized steady state production code actually runs at;
   a single-pass measurement blends both regimes into one misleading
   number.
2. **Dead code elimination** — if the computed result is never used
   observably, the JIT is free to delete the computation the benchmark
   meant to measure, silently reducing "run this a million times" toward
   "run this zero times" while still reporting a fast, meaningless time.
3. **No control over JVM state** — one in-process run shares its JVM with
   whatever else has happened in that process (prior GC activity,
   background compiler threads, OS scheduling noise) — a single sample,
   not a controlled, repeatable measurement.

### What JMH does about each problem
- **Warm-up**: `@Warmup(iterations = N, time = T)` runs and *discards* N
  full iterations before `@Measurement` begins, ensuring the JIT has
  actually reached steady state before anything is recorded.
- **Dead code elimination**: JMH requires `@Benchmark` methods to *return*
  a value (which the generated harness consumes) or explicitly consume one
  via `Blackhole.consume(...)` — both are JIT-aware mechanisms specifically
  designed to prevent the compiler from proving the computation is
  unobservable and eliminating it.
- **JVM state control**: `@Fork(value = N)` runs the benchmark in `N`
  completely fresh JVM processes and averages across them, isolating each
  measurement from whatever the benchmark harness itself (or a previous
  benchmark in the same suite) did to JIT/GC state.

### JMH skeleton for `OrderService.totalSpentByImperative` vs. `totalSpentByStreams`
See **`src/.../OrderServiceJmhBenchmark.java`** for the full, heavily
commented skeleton — **it will not compile in this repo as-is**: it
requires the `org.openjdk.jmh:jmh-core` and
`org.openjdk.jmh:jmh-generator-annprocess` Maven dependencies (plus the
shade plugin, to produce a runnable `benchmarks.jar`), and this repo has no
Maven module anywhere yet (Module 5 introduces Maven project-wide) — nor
does this sandbox have Maven or internet access to fetch the dependency.
The file's JMH-annotated code is deliberately left as a commented block
with an explanation of exactly what to uncomment and where it would live
once a real `jmh-benchmarks/` Maven module exists. The shape, summarized:

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2)
public class OrderServiceJmhBenchmark {

    @State(Scope.Benchmark)   // one shared instance per fork -- read-only fixtures
    public static class OrderServiceState {
        OrderService orderService;
        Customer customer;
        List<Order> orders;

        @Setup                // runs ONCE per fork, before warm-up -- never measured
        public void setUp() { /* build inventory, orderService, customer, orders */ }
    }

    @Benchmark
    public BigDecimal imperative(OrderServiceState state) {
        return state.orderService.totalSpentByImperative(state.customer, state.orders);
    }

    @Benchmark
    public BigDecimal streams(OrderServiceState state) {
        return state.orderService.totalSpentByStreams(state.customer, state.orders);
    }
}
```

Run (once built as a real Maven module): `mvn clean package` then
`java -jar target/benchmarks.jar OrderServiceJmhBenchmark`.

### When to use / when NOT to use JMH
- Use JMH when you need a genuinely trustworthy answer to "which of these
  two small pieces of code is faster, and by how much" — the streams-vs-
  loop question this module's skeleton targets is exactly this shape.
- Don't use a microbenchmark result (JMH or otherwise) to justify a
  production change without also considering realistic input sizes and
  call frequency — a microbenchmark showing "loop is 40% faster than
  streams for summing 20 orders" is measuring something real, but at
  real-world scale (20 orders, called occasionally) the *absolute*
  difference is almost certainly irrelevant next to network/DB latency;
  see java-basics README section 4's discussion of exactly this trade-off.
- Don't reach for JMH for anything beyond genuinely hot, small,
  CPU-bound code — I/O-bound operations, database calls, or anything with
  meaningful external side effects need a different kind of load/
  integration testing (Module 10), not a microbenchmark.

### Trade-offs & performance implications
- JMH's own overhead (forking JVMs, running warm-up iterations you
  discard) makes a full benchmark run take substantially longer than a
  naive timing loop — a worthwhile trade for a result you can actually
  trust, but not something you'd run on every CI build without
  consideration for build time budget (many teams run JMH suites on a
  schedule or on-demand, not on every commit).

### Enterprise examples
- A JVM library maintainer (e.g. a JSON serialization library, a
  collections library) using JMH as the standard way to validate that a
  proposed optimization actually improves performance before merging it —
  exactly the rigor a naive "I timed it and it seems faster" PR
  description lacks.
- A team debating whether to replace a hot `Collectors.groupingBy`-based
  reporting pipeline with a hand-written loop settling the question with a
  JMH benchmark at realistic production data volumes, rather than
  litigating "streams vs. loops" opinions in code review.

### Common mistakes
- Writing a benchmark method that doesn't return or consume its result,
  silently falling back into the exact dead-code-elimination trap JMH
  exists to prevent.
- Under-provisioning warm-up iterations for a method that takes unusually
  long to reach steady state (megamorphic call sites, very large methods
  that inline slowly) and trusting the result anyway.
- Benchmarking with unrealistic input sizes (a 3-element list) and
  generalizing the result to production-scale inputs (thousands of
  elements) without re-validating — algorithmic complexity differences
  between two approaches often don't show up until scale.

---

## Next module

Module 12 — AWS (IAM, VPC, EC2/ECS/EKS, Lambda, API Gateway, S3, SQS/SNS,
EventBridge, Step Functions, CloudWatch, Secrets Manager, RDS, DynamoDB,
CloudFront, Route53, Terraform, Well-Architected Framework), per the
repo-root roadmap — explained locally, nothing deployed. Not started until
this module is confirmed solid.
