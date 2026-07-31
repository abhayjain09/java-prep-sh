# Module 11 — Interview Questions

Organized by topic, then by level (beginner → intermediate → senior →
scenario). JVM internals questions are a **standard bar-raiser at
high-scale companies** — Amazon, Google, and Microsoft in particular
routinely probe GC/JIT/memory-model understanding in performance-focused
and "senior"/"staff" loops, on the theory that a candidate who can only
reason about *correctness* and not *runtime cost* will eventually cause an
expensive production incident at scale. S&P Global, JPMorgan, and Goldman
Sachs ask these questions specifically in the context of low-latency
trading/settlement systems, where GC pause tail latency has direct
financial consequences. Expect these questions to follow directly after a
system-design or coding question, not as a standalone trivia round.

---

## JVM Memory Model (Heap, Stack, Metaspace, Off-Heap)

**Beginner:** "What's the difference between the heap and the stack in
the JVM?"
*Ideal answer:* The heap holds every object (`new Order(...)`), shared
across all threads, garbage collected. The stack is per-thread, holds
method call frames (local variables — both primitives and object
*references* — and the call chain), never shared, never garbage collected
(frames are popped automatically as methods return).
*Follow-up:* "Is a local variable `Order order = new Order(...)` on the
heap or the stack?" → Both, but for different parts: the *reference*
`order` is a stack-local variable; the actual `Order` object it points to
is on the heap.

**Intermediate:** "What replaced PermGen in Java 8, and why?"
*Ideal answer:* Metaspace — native memory (outside the heap) for class
metadata, growing dynamically instead of being capped by a fixed
`-XX:MaxPermSize`. PermGen's fixed sizing routinely caused `OutOfMemoryError:
PermGen space` in applications that loaded many classes dynamically
(app servers, heavy reflection/proxy use); metaspace removes that specific
failure mode by drawing from native memory instead, bounded (optionally)
by `-XX:MaxMetaspaceSize`.
*Follow-up:* "Does metaspace being 'unbounded by default' mean it can't
leak?" → No — a classloader leak (a `ClassLoader` kept alive by a
lingering reference after it should have been collected) keeps every class
it loaded, and their metadata, alive forever, now consuming unbounded
*native* memory instead of triggering a clean heap OOM.

**Senior:** "A service using `java.nio` direct `ByteBuffer`s for bulk file
I/O reports steadily increasing native memory usage that doesn't show up
in heap monitoring. Walk through your diagnosis."
*Ideal answer:* Direct buffers are off-heap; their backing native memory is
only freed when the wrapping `ByteBuffer` Java object is GC'd and its
`Cleaner` runs — if the app allocates fresh direct buffers per request
instead of pooling/reusing them, and GC pressure on the (possibly mostly-
empty) heap is low enough that minor GCs are infrequent, those `ByteBuffer`
wrapper objects (and therefore their native backing memory) can pile up
waiting for a GC that has no heap-pressure reason to run soon. Fix: pool
and reuse direct buffers rather than allocate-per-request, and set
`-XX:MaxDirectMemorySize` explicitly so this failure mode produces a clear
`OutOfMemoryError: Direct buffer memory` instead of silently exhausting
host memory.
*Follow-up:* "Why doesn't a normal heap dump help here?" → Because the
actual leaked memory is native, not on the heap — a heap dump would only
show you the (small) `ByteBuffer` wrapper objects, not the megabytes each
one points to; Native Memory Tracking (`-XX:NativeMemoryTracking=summary`
+ `jcmd <pid> VM.native_memory`) is the right tool instead.

**Scenario:** "Your `OrderService` handles a sudden 10x traffic spike.
Heap usage graphs look fine (well under `-Xmx`), but P99 latency climbs
sharply. What are the memory-model-related hypotheses you'd check, in
order, before assuming it's unrelated to memory at all?"
*Ideal answer:* (1) Minor GC *frequency*, not just heap occupancy — 10x
traffic means 10x the `Order`/`OrderLine` allocation rate, which can push
minor GCs from "rare, unnoticed" to "frequent enough that their cumulative
pause time matters," even with occupancy graphs looking fine. (2) Whether
any per-request allocation is unexpectedly large (an accidental full-table
scan materialized into memory, a defensive copy of a large collection per
request). (3) Whether the traffic spike also increased class-loading (e.g.
dynamic proxy generation per new tenant) enough to pressure metaspace or
cause synchronized class-loading contention. Confirm with a JFR recording
(README section 5) rather than guessing from dashboards alone.

---

## Garbage Collection

**Beginner:** "What's the generational hypothesis, and why does the JVM's
heap layout reflect it?"
*Ideal answer:* Most objects die young; a small minority live a long time.
The heap is split into a young generation (collected frequently and
cheaply, since most of it turns out to be garbage) and an old generation
(collected rarely, since surviving to get there is evidence an object
tends to stay alive).
*Follow-up:* "Give a concrete example from the Order/Inventory domain of
an object that dies young, and one that would legitimately get promoted."
→ A per-request `Order`/`OrderLine` created in `placeOrder` and discarded
once the response is sent (young); a long-lived `Inventory` instance held
for the service's entire lifetime (old, correctly promoted).

**Intermediate:** "Why is G1 the default collector since Java 9, and when
would you deliberately choose something else?"
*Ideal answer:* G1 targets a configurable pause-time *goal*
(`-XX:MaxGCPauseMillis`) via region-based, mostly-concurrent collection,
giving a good throughput/latency balance for most general-purpose services
without hand-tuning — a sensible default. Choose Parallel instead for
throughput-only batch/offline jobs with no latency SLA; choose ZGC or
Shenandoah instead when you have a *measured*, strict tail-latency
requirement G1's occasional longer pause can't reliably meet, especially
on very large heaps.
*Follow-up:* "What's the actual mechanism that lets ZGC keep pauses
sub-millisecond on multi-TB heaps, at a high level?" → Concurrent
evacuation using colored pointers and load barriers — most of the
relocation work happens concurrently with the application running, with
only very short, heap-size-independent stop-the-world pauses for the parts
that genuinely require it.

**Senior:** "Walk me through reading a GC log line: `92M->48M(128M)
17.482ms`. What would make you concerned, and what wouldn't?"
*Ideal answer:* Heap usage went from 92MB to 48MB (out of 128MB current
capacity) in a 17.482ms pause. A single line like this, alone, isn't
concerning — that's a normal minor GC reclaiming a healthy majority of
Eden in a short pause. What WOULD be concerning: watching this number
across many consecutive events and seeing the *post-GC* value (`48M` here)
trend upward over time on stable traffic — a rising floor, not the pause
duration alone, is the classic memory-leak signature (see
`EXERCISES.md`'s scenario exercise for a full worked example), because it
means each collection is reclaiming a shrinking fraction of what was
allocated, implying something is accumulating that shouldn't be.
*Follow-up:* "At what point would you escalate from 'watch it' to 'this is
an active incident'?" → When Full GCs start appearing and their frequency
is increasing (not just occurring once) — a single Full GC can be
legitimate ergonomic behavior; an accelerating pattern of them on stable
traffic is not.

**Scenario:** "A trading platform team wants to switch from G1 to ZGC
purely because 'lower pause times sound better.' How do you respond?"
*Ideal answer:* Ask for the actual measured problem first — a specific P99/
P99.9 latency SLA that G1 is missing, backed by GC log or JFR data, not a
general preference. ZGC's lower worst-case pause typically trades off some
raw throughput and higher constant CPU overhead from doing more work
concurrently; for a workload that isn't actually pause-sensitive at the
sub-few-ms level G1 already delivers, switching adds operational
complexity (a less commonly tuned collector, different diagnostic
vocabulary) without a matching benefit. Recommend measuring first — same
workload, same hardware, both collectors, real traffic shape — before
committing to the switch.

---

## Class Loading

**Beginner:** "Name the three main classloaders in the standard hierarchy
and what each is responsible for."
*Ideal answer:* Bootstrap (native, loads `java.base`/core JDK classes, no
Java-level object — `getClassLoader()` returns `null`), Platform (other
JDK platform modules, renamed from "Extension" in Java 9), Application/
System (everything on your classpath — your own compiled classes).

**Intermediate:** "What's the practical difference between
`ClassNotFoundException` and `NoClassDefFoundError`, and how do you tell
which you're dealing with from a stack trace alone?"
*Ideal answer:* `ClassNotFoundException` (checked) is thrown by an
*explicit* by-name load (`Class.forName`, `loadClass`) when the class
genuinely isn't found anywhere in the delegation chain — a classpath/
dependency problem. `NoClassDefFoundError` (unchecked, a `LinkageError`)
is thrown when a class that *was* available/loadable earlier fails an
*implicit* reference later — usually a deploy/classpath mismatch, or a
static initializer that already threw once (permanently marking the class
erroneous per JLS §12.4.2). From a stack trace alone: the exception type
itself tells you which case, but for `NoClassDefFoundError` specifically
you should immediately go search earlier logs for an
`ExceptionInInitializerError` on that same class name — that's usually
where the real root cause was actually logged.
*Follow-up:* "Why is the message on a `NoClassDefFoundError` from a failed
static initializer often unhelpful?" → Because the original exception that
caused `<clinit>` to fail isn't attached to the `NoClassDefFoundError` at
all on the *second and later* touches — only the very first touch's
`ExceptionInInitializerError` carries the real cause.

**Senior:** "Explain how the Java 9+ module system changes the picture for
a library that reflectively accesses JDK-internal classes."
*Ideal answer:* The module system layers explicit `requires`/`exports`
declarations, plus **strong encapsulation**, on top of the existing
classloader delegation hierarchy (it doesn't replace it — the same three
built-in classloaders still load modules). Non-exported packages inside a
module are inaccessible to other modules by default, including via
reflection (`setAccessible(true)` no longer bypasses this) — code that
used to reach into `sun.*`/JDK-internal packages now needs an explicit
`--add-opens` flag at the JVM level, or must stop relying on that access
entirely. This is why upgrading a reflection-heavy dependency stack (older
ORMs, some serialization libraries) across a Java 8 → 17 boundary is a
real compatibility project, not a version-number bump.
*Follow-up:* "Does an application with no `module-info.java` of its own
(the 'unnamed module,' classpath-based) get any protection from this?" →
It's still subject to strong encapsulation for JDK-internal packages
specifically (the JDK's own modules ARE named and do enforce it) — an
unnamed-module application can still hit `--add-opens`-shaped failures
from a transitive dependency's reflective JDK-internal access, even though
the application itself never opted into the module system.

**Scenario:** "A colleague says 'this class exists, I can see the .class
file in the JAR, but I still get `NoClassDefFoundError` at runtime — that
doesn't make sense.' How do you investigate?"
*Ideal answer:* First check whether the class was loadable and used
successfully earlier in the same JVM run (search logs for an earlier
`ExceptionInInitializerError` on that exact class — a failed static
initializer is the single most common cause of "the file is right there
but it still fails"). If there's no earlier initializer failure, check for
a classpath/classloader mismatch instead: is the `.class` file that exists
in the JAR actually on the classloader making the *failing* request's
classpath (common in app-server/OSGi environments where isolation means
"exists in the deployment" doesn't imply "visible to this specific
classloader")? Confirm with `-Xlog:class+load=info` to see exactly which
classloader loaded (or failed to find) the class at runtime.

---

## JIT Compilation

**Beginner:** "What's the difference between the interpreter, C1, and C2?"
*Ideal answer:* The interpreter executes bytecode directly (slow per-call,
instant availability, also gathers profiling data). C1 compiles quickly
with light optimization (favors low compile latency). C2 compiles slowly
but aggressively (inlining, loop unrolling, escape analysis — favors peak
throughput for genuinely hot code). Tiered compilation (default since Java
8) uses all three, progressively, based on how hot a method proves to be.

**Intermediate:** "Why can a microbenchmark that doesn't account for
warm-up give a misleadingly *fast* result, not just a misleadingly slow
one?"
*Ideal answer:* Two independent effects can each make a naive benchmark
look artificially fast: (1) dead code elimination — if the JIT can prove
the loop's result is never observably used, it can delete the computation
entirely, timing "do nothing" instead of the intended workload; (2) a
result measured entirely within a single warmed-up JVM session where the
method happened to already be C2-compiled from a PRIOR unrelated call
earlier in the same test run — inflating perceived steady-state
performance for a code path that, in a fresh production JVM immediately
after a deploy, would actually run through the much slower cold-start
tiers first.

**Senior:** "Explain escape analysis and scalar replacement, and give an
example of code where a seemingly wasteful loop might not actually
allocate much once it's hot."
*Ideal answer:* Escape analysis determines whether an object created
inside a method can ever be observed outside that method/thread (stored,
returned, shared across threads). If C2 proves it cannot, it can apply
scalar replacement — decomposing the object into its fields, kept in
registers/on the stack, skipping heap allocation (and all GC cost)
entirely. Example: a tight loop that constructs a temporary `OrderLine`
purely to call `.lineTotal()` and immediately discards it
(`EscapeAnalysisDemo.sumLineTotalsNonEscaping`) is a plausible candidate —
contrast with a version that adds each `OrderLine` to a list it returns
(`buildEscapingLines`), where every instance provably escapes and scalar
replacement is never legal.
*Follow-up:* "How would you actually confirm scalar replacement is
happening for a specific hot method, rather than just hypothesizing?" →
Diagnostic JIT flags (`-XX:+UnlockDiagnosticVMOptions
-XX:+PrintEliminateAllocations`, typically needing a debug/fastdebug JDK
build for full output) or comparing JFR allocation-rate-by-call-site data
for that method before and after it's warmed up — never from reading
source code alone.

**Scenario:** "A previously-fast hot method in a payment-processing
service suddenly gets measurably slower after a new `PaymentMethod`
implementation is added elsewhere in the codebase, even though the hot
method itself wasn't touched. How do you explain this to the team?"
*Ideal answer:* The hot method likely has a call site over the
`PaymentMethod` interface that C2 had speculatively optimized assuming it
was monomorphic or bimorphic (only 1-2 concrete implementations ever
observed). Adding a third implementation can push that call site to
**megamorphic**, which the JIT can no longer confidently speculatively
inline — the method may deoptimize and recompile with a more conservative
(virtual dispatch, not inlined) version of that call, a real, measurable
slowdown despite the "hot" method's own source code being unchanged. This
is a good example of why performance regressions sometimes trace back to
seemingly unrelated code changes elsewhere in the same call graph.

---

## Profiling with JFR / JMC

**Beginner:** "What is JFR, and how is it different from attaching a
traditional profiler?"
*Ideal answer:* JFR (Java Flight Recorder) is a low-overhead event
recording framework built into the JVM, safe to run continuously in
production (unlike most traditional profilers, which have overhead high
enough that they're typically only attached during active investigation).
JMC is the GUI for analyzing the resulting `.jfr` file.

**Intermediate:** "Walk through how you'd start a JFR recording against an
already-running production process, without restarting it."
*Ideal answer:* `jcmd <pid> JFR.start duration=120s
filename=incident.jfr settings=profile` — this attaches to the live
process via `jcmd`, no restart needed, which matters because restarting
would lose the exact in-progress conditions (load pattern, data shape)
you're trying to capture. Stop/inspect with `jcmd <pid> JFR.stop` /
`JFR.check`.
*Follow-up:* "What's the trade-off between the `default` and `profile`
JFR settings?" → `profile` captures more detail (finer-grained
stack sampling, more event types) at somewhat higher overhead; `default`
is tuned to be safe for always-on continuous production use. Use `default`
continuously, reach for `profile` only for a bounded investigation window.

**Senior:** "`OrderService.placeOrder` is reported slow under load. You
have a JFR recording from the incident window. Walk through your
analysis, in order, and explain what each tab/view is telling you."
*Ideal answer:* (1) GC tab — rule in/out GC-pause-bound slowness first
(elevated minor-GC frequency correlating with the slow window would point
to an allocation-rate problem). (2) Hot Methods (execution samples) — if
not GC-bound, where is CPU time actually concentrated? (3) Lock Instances
— is a monitor (e.g. a naive `synchronized` wrapping the whole
`placeOrder` call, as in `LockContentionUnderLoadDemo`) showing high
summed blocked time, meaning threads are queuing behind each other instead
of doing real work? (4) Allocation tab — is a specific call site
allocating far more than expected (an accidental extra defensive copy per
call, as in `AllocationPatternsDemo`'s wasteful version)? The point of
going in this order is that each view rules in or out a distinct causal
category before you commit engineering time to a fix targeted at the
wrong layer.
*Follow-up:* "Suppose Lock Instances shows heavy contention on
`Inventory`'s implicit monitor. What's your fix, and why not just remove
the lock?" → Removing the lock reintroduces the original race condition
(lost updates on `stockBySku`); the correct fix narrows the lock's scope —
per-SKU locking or `ConcurrentHashMap.compute()` per SKU (java-basics
Exercise 5, full treatment in Module 3) — so only threads contending for
the *same* SKU ever wait on each other, instead of every order in the
system serializing behind one global lock.

**Scenario:** "Your team's default posture has been 'we only attach a
profiler when something goes wrong.' Make the case, to a skeptical
engineering manager worried about overhead, for running JFR continuously
in production instead."
*Ideal answer:* JFR's overhead at the default settings is low enough
(commonly cited in the low single-digit percent range) to be an acceptable
always-on cost, and the payoff is qualitative, not just quantitative: an
incident that happens once, briefly, at 3am is often impossible to
reproduce on demand — with continuous JFR already running, the data
needed to diagnose it already exists the moment someone asks "what
happened," instead of requiring the problem to recur *after* a profiler is
attached (which itself risks the profiler's own overhead perturbing the
very behavior being chased). Frame it as an insurance cost with a
measured, bounded premium, not an open-ended performance risk.

---

## JMH (Java Microbenchmark Harness)

**Beginner:** "Why not just time a loop with
`System.currentTimeMillis()` before and after?"
*Ideal answer:* Three independent problems: JIT warm-up (early iterations
run interpreted/C1, not at steady-state C2 speed), dead code elimination
(an unused result can be optimized away entirely, measuring nothing), and
no control over JVM state (one in-process run is one noisy sample, with no
isolation from other JIT/GC activity in the same process).

**Intermediate:** "What do `@Warmup` and `@Fork` each protect against,
specifically?"
*Ideal answer:* `@Warmup(iterations, time)` runs and discards iterations
before measurement begins, ensuring the code under test has actually
reached JIT steady state before anything is recorded — protects against
warm-up skew. `@Fork(value)` runs the whole benchmark in N completely
fresh JVM processes and averages — protects against one run's JIT/GC
history contaminating another's, and against any single run being an
outlier due to transient host conditions.
*Follow-up:* "Why does a `@Benchmark` method need to return a value (or
call `Blackhole.consume`)?" → Otherwise the JIT can legally prove the
entire computation is unobservable and eliminate it — exactly the dead-
code-elimination problem JMH exists to prevent; returning the value (JMH's
generated harness consumes it) or explicitly consuming it via `Blackhole`
keeps the computation "alive" from the compiler's point of view.

**Senior:** "You benchmark `OrderService.totalSpentByImperative` vs.
`totalSpentByStreams` with JMH on a 20-element `orders` list and find
them within noise of each other. A teammate says 'see, streams are proven
just as fast, let's use streams everywhere.' How do you respond?"
*Ideal answer:* The benchmark is valid for what it measured — at that
specific, small input size, on that specific JVM/hardware. It does NOT
generalize to "streams are always as fast as loops" — at much larger
element counts, or with primitive-heavy pipelines that box unnecessarily
(`Stream<Integer>` vs `IntStream`), a real difference can emerge that a
20-element benchmark simply can't reveal. Recommend re-running the same
JMH benchmark at production-realistic data volumes (and note java-basics
README section 4's broader point: for typical business-logic volumes, the
difference is very likely to remain irrelevant next to I/O/DB latency
anyway — this is a case for measuring at realistic scale, not for a
blanket rule in either direction).

**Scenario:** "A candidate submits a PR claiming a 3x speedup from
replacing a stream pipeline with a hand-written loop, backed by a
`System.nanoTime()`-around-a-single-call benchmark run once, locally. As
the reviewer, what do you ask for before approving?"
*Ideal answer:* Ask for a JMH benchmark instead of the ad hoc timing —
specifically request: warm-up iterations reported separately from
measurement, multiple forks, and the actual data volume/shape used (does
it match production?). Point out that a single `nanoTime()` call, run
once, cannot rule out JIT warm-up skew or dead code elimination as the
entire explanation for the observed "3x" — and that even a real, JMH-
confirmed 3x difference on a microbenchmark needs to be weighed against
the method's actual call frequency and the absolute time involved in
production before it justifies a readability trade-off (per java-basics
README section 4's "measure before optimizing away readability"
guidance).
