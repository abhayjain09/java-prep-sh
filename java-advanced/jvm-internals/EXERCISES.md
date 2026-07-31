# Module 11 — Exercises

Do these on your own machine — this sandbox has no `java`/`javac`, no
Maven, and no ability to run JFR recordings or JMH benchmarks. Compile
against `java-basics`'s domain/service classes as shown in
[EXPLANATION.md](EXPLANATION.md)'s "How to compile and run" section. Work
through them in order; the last one is a scenario diagnosis exercise that
draws on everything before it.

## 1. (Beginner) Observe GC logging for real

Run `AllocationPatternsDemo` with GC logging enabled:

```bash
java -Xlog:gc*:file=gc.log:time,uptime,level,tags -cp out com.interviewprep.orders.jvm.AllocationPatternsDemo
```

Open `gc.log`. Find at least one `Pause Young` line. Using README section
2's field-by-field walkthrough as a guide, answer: how many minor GCs
happened during the run? What was the *largest* single pause duration you
see? Now increase `volume` in `AllocationPatternsDemo.main()` from
`200_000` to `2_000_000` and re-run — did the number of minor GCs increase
roughly proportionally, or was the relationship different? Why might it
not be perfectly linear (hint: think about what a bigger loop does to JIT
warm-up state partway through).

**Check yourself:** if you ran this with `-Xmx32m` (deliberately small)
versus the JVM's default heap size, what do you expect to change about GC
frequency, and did your observation match?

## 2. (Beginner) Reproduce the wasteful-vs-lean allocation gap with real data

`AllocationPatternsDemo` compares a wasteful and a leaner allocation
pattern but only prints wall-clock time from a single, cold, unfair
comparison (the class's own comments say so). Add a THIRD variant,
`evenLeanerVersion`, that goes one step further than `leanerLowerAllocationVersion`
by avoiding the string concatenation `"ORD-LEAN-" + i` per iteration (build
the id differently, e.g. reuse a `StringBuilder` and reset it, or use
`Integer.toString(i)` concatenated more cheaply) — then explain in a
comment why this specific change matters less than the fixes already made
in `leanerLowerAllocationVersion`, given what you now know about young-gen
GC pressure and short-lived object cost.

## 3. (Intermediate) Confirm ClassNotFoundException vs. NoClassDefFoundError practical difference yourself

`ClassLoaderHierarchyDemo` reproduces both exception types. Extend it:
write a `main()` variant (or a new class) that catches
`NoClassDefFoundError`, and — instead of just printing the message — call
`e.getCause()` on it. What do you get, and why does that confirm the
README's claim that the *original* `RuntimeException` from `<clinit>` is
gone by the second touch? Then, separately: create a class
`FailsOnlyTheFirstTime` whose static initializer throws only if a static
counter is `0` (incrementing it first) — trigger it twice and observe that
it STILL throws `NoClassDefFoundError` on the second touch even though
your `<clinit>` logic itself "would have" succeeded the second time. What
does this prove about how permanently the JVM marks a class erroneous?

## 4. (Intermediate) Escape analysis: make an object non-escaping become escaping, and reason about the consequence

Take `EscapeAnalysisDemo.sumLineTotalsNonEscaping`. Modify it (call the new
version `sumLineTotalsWithLeak`) so that the `OrderLine` created each
iteration is *also* appended to a `List<OrderLine>` field on the class,
purely as a side effect never read again. Explain in a comment: does this
change make the object escape, in the sense escape analysis cares about?
Why would this same one-line change (which looks harmless — "just also
keep a log") potentially turn a scalar-replacement candidate into a real
heap allocation on every call, with a real, measurable GC-pressure cost at
volume — even though nothing about the *return value* of the method
changed at all?

## 5. (Senior) Write and run a real JMH benchmark

Set up an actual JMH-enabled Maven module (you'll need internet access and
Maven on your own machine — the JMH team's `jmh-java-benchmark-archetype`
is the fastest way to scaffold one, or hand-write a minimal `pom.xml` with
the two dependencies from README section 6). Copy the commented-out class
body from `OrderServiceJmhBenchmark.java` into a real `.java` file in that
module (uncommenting the imports and the class), wire up the `@Setup`
method against `java-basics`'s actual classes, and run it for real with
realistic data (try both 20 orders and 20,000 orders in the `@State`
fixture). Report: at which size, if any, does `totalSpentByStreams`
measurably diverge from `totalSpentByImperative`, and does the direction
of the difference match or contradict java-basics README section 4's
prediction ("boxing overhead means streams may be a bit slower on numeric-
heavy pipelines, but the difference is usually noise for typical business
logic volumes")?

## 6. (Scenario) Diagnose a GC log excerpt

You're on call. A teammate pastes you this excerpt from `OrderService`'s
production GC log and says "orders started timing out around 14:32, can
you tell what happened from this?" (This excerpt is **hand-constructed for
this exercise** — it is not a real capture, but every field and value
shape in it is realistic and consistent with actual G1 unified-logging
output; treat it as a diagnostic puzzle, not a genuine incident record.)

```
[2026-07-31T14:30:01.000+0000][100.0s][info][gc] GC(340) Pause Young (Normal) (G1 Evacuation Pause) 512M->128M(2048M) 12.100ms
[2026-07-31T14:30:15.000+0000][114.0s][info][gc] GC(341) Pause Young (Normal) (G1 Evacuation Pause) 640M->140M(2048M) 13.400ms
[2026-07-31T14:30:29.000+0000][128.0s][info][gc] GC(342) Pause Young (Normal) (G1 Evacuation Pause) 700M->155M(2048M) 14.900ms
[2026-07-31T14:31:40.000+0000][199.0s][info][gc] GC(343) Pause Young (Normal) (G1 Evacuation Pause) 980M->410M(2048M) 22.300ms
[2026-07-31T14:31:52.000+0000][211.0s][info][gc] GC(344) Pause Young (Normal) (G1 Evacuation Pause) 1050M->480M(2048M) 28.700ms
[2026-07-31T14:32:03.000+0000][222.0s][info][gc] GC(345) Pause Young (Normal) (G1 Evacuation Pause) 1120M->560M(2048M) 35.100ms
[2026-07-31T14:32:15.000+0000][234.0s][info][gc] GC(346) Pause Full (Ergonomics) 1800M->1650M(2048M) 2450.000ms
[2026-07-31T14:32:40.000+0000][259.0s][info][gc] GC(347) Pause Full (Ergonomics) 1900M->1780M(2048M) 2810.000ms
```

Answer, with reasoning tied to specific fields in the log:
1. What's the trend in the "after GC" heap usage number (the middle value
   in `X->Y(Z)`) across `GC(340)` through `GC(345)`, and what does a
   *rising floor* like that (as opposed to usage returning to a similar
   low value after every minor GC) usually indicate?
2. What changed at `GC(346)`, and why would that specific event correlate
   with orders "timing out" in a way the earlier minor GCs (all under
   40ms) would not?
3. Is this pattern more consistent with (a) a temporary traffic spike that
   will resolve itself, or (b) a genuine memory leak? What ADDITIONAL
   evidence (from JFR, from application logs, from a heap dump) would you
   want before committing to a root cause and a fix, and why shouldn't you
   just immediately reach for "increase `-Xmx`" as the fix without that
   additional evidence?
4. Referencing java-basics's `Inventory`/`OrderService` design: name one
   concrete, plausible application-level bug (not a JVM tuning problem)
   that would produce exactly this "old gen usage climbing across many
   minor GCs, then a slow Full GC" signature, and explain the mechanism.
