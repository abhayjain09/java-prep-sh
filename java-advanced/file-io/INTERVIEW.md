# Module 2 — Interview Questions

Organized by topic, then by level (beginner → intermediate → senior →
scenario). Each includes an ideal answer outline and likely follow-ups.
File-handling and I/O questions show up across the board at Java-heavy
shops — S&P Global, JPMorgan, and Goldman Sachs commonly probe streaming
vs. loading-into-memory trade-offs for batch/file-processing roles; Amazon
and Microsoft loops frequently include a "design a directory-watching /
file-processing pipeline" system-design-adjacent question; Oracle and
Adobe interviews (given both companies' deep Java-platform investment) go
further into JVM-level I/O mechanics (buffering, NIO channels) than most.

---

## `java.io` vs `java.nio.file`

**Beginner:** "What's the difference between `java.io.File` and
`java.nio.file.Path`?"
*Ideal answer:* `File` (Java 1.0) represents a pathname with a weak error
model — most failures return `false`/`-1` instead of a descriptive
exception. `Path` (Java 7, NIO.2) is an immutable pathname type used with
the `Files` utility class, which throws specific exceptions
(`NoSuchFileException`, `AccessDeniedException`, ...) describing *why* an
operation failed. `Path`/`Files` also added capabilities `File` never had:
symbolic link handling, file attribute views, atomic moves, and
`WatchService`.
*Follow-up:* "Can you convert between them?" → Yes, `file.toPath()` and
`path.toFile()` interconvert freely, which is why a legacy `File`-based
codebase can be migrated incrementally rather than all at once.

**Intermediate:** "Why does `Files.lines(path)` need to be inside
try-with-resources, when most `Stream`s don't need closing?"
*Ideal answer:* `Files.lines()` returns a `Stream<String>` backed by an open
file handle read lazily as the stream is consumed — unlike a `Stream` over
an in-memory collection, this one holds a live OS resource that must be
released. Forgetting to close it compiles and runs fine in a quick test,
and leaks a file descriptor in production until file-descriptor exhaustion
surfaces as an unrelated-looking `"too many open files"` error.
*Follow-up:* "Name another `Stream`-returning NIO.2 method with the same
gotcha." → `Files.walk(path)`, for the same reason (backed by open
directory/file handles as it lazily traverses).

**Senior:** "You're reviewing a PR that reads a config file with
`Files.readString(path)`. When would you push back on that in code review?"
*Ideal answer:* Not for a genuinely small, bounded file (a config file is
usually fine) — but push back if the file's size isn't actually bounded, or
if "config file" later becomes "config file that could grow" without
anyone revisiting this line. The real signal to look for: does anything
about this file's *nature* guarantee it stays small forever? If not, prefer
a streaming read and document the assumption if a one-shot read is kept.

**Scenario:** "A junior engineer says 'I'll just use `File` since it's what
I learned first and it still works.' How do you respond?"
*Ideal answer:* Acknowledge `File` still works and interop is trivial via
`toPath()`/`toFile()` — this isn't a "your code is broken" conversation.
Recommend `Path`/`Files` for new code specifically for the typed-exception
error handling and NIO.2-only capabilities (watching, attribute views,
atomic moves), and suggest incremental conversion of touched methods rather
than a wholesale rewrite of stable, working `File`-based code.

---

## Streaming / large-file CSV import

**Beginner:** "Why does reading a file line-by-line use less memory than
`Files.readAllLines()`?"
*Ideal answer:* `Files.readAllLines()` reads the entire file and returns
every line as a `List<String>` held in memory at once — memory use scales
with file size. A `BufferedReader.readLine()` loop holds only the current
line (plus a small internal buffer) at any moment — memory use for the
*reading* step is roughly constant regardless of file size.
*Follow-up:* "So is line-by-line reading always the better choice?" → Not
automatically — see `CsvOrderImporter`'s own "honest caveat": if you then
accumulate everything you parse into one big `List` anyway (as this
module's importer does, returning `List<Order>`), you've moved the memory
problem, not eliminated it. True scalability needs the *processing*, not
just the *reading*, to avoid holding everything at once.

**Intermediate:** "Walk through what would go wrong, concretely, if a
retailer's nightly order-import file grew from 10 MB to 8 GB and the import
job used `Files.readAllLines()`."
*Ideal answer:* At 10 MB, `readAllLines()` briefly holds roughly 10 MB (plus
String object overhead — each line becomes a separate `String` with its own
object header, meaningfully more than the raw byte count) — invisible next
to a typical multi-GB heap. At 8 GB, the same call needs to hold the whole
file's lines simultaneously before the import logic runs at all — likely
exceeding the heap outright (`OutOfMemoryError`) or, even if it technically
fits, causing significant GC pressure that slows or stalls the rest of the
JVM. The failure is also nondeterministic in *when* it manifests — it
depends on concurrent memory pressure from everything else running in that
JVM at the time, which is part of why this class of bug is notoriously hard
to reproduce reliably in a staging environment sized differently from
production.
*Follow-up:* "How would you catch this risk before it hits production?" →
Load/volume testing against production-representative file sizes, not just
small fixtures; a code-review habit of asking "is this file's size actually
bounded?" for any `readAllLines`/`readAllBytes`/`readString` call.

**Senior:** "Design a CSV import pipeline that needs to handle files with
tens of millions of rows without unbounded memory growth, given that a
single 'order' spans multiple CSV rows (like this module's format)."
*Ideal answer:* The read side stays exactly as `CsvOrderImporter` already
does it — line-by-line via a buffered reader. The change is on the
*accumulation* side: instead of returning a `List<Order>` (which itself
grows unboundedly with row count), the importer should process and emit
each `Order` via a callback/consumer as soon as it's known to be complete,
then discard it, keeping only a small working set of "orders currently
being assembled" in memory at once. Detecting "an order is complete" is the
hard part for a row-per-line format with no per-order row count declared
upfront — options include: relying on the input being sorted/grouped by
orderId so a change in orderId signals the previous order is done (fragile
if the upstream system doesn't guarantee ordering), or accepting that
without a sort guarantee, a fully-streaming approach needs a bounded
LRU-style eviction policy accepting some risk of processing an order before
its last line arrives out of order, which needs to be explicitly evaluated
against the file format's real guarantees rather than assumed.
*Follow-up:* "What would you check before believing the upstream file is
sorted by orderId?" → The system's own export documentation/contract if one
exists (and whether it's actually enforced, versus true "by convention, so
far"); failing that, defensively validate the assumption at read time
(track the most recently completed orderId and flag/alert if a supposedly
new orderId reappears later in the file) rather than silently trusting it.

**Scenario:** "A batch import job has been running fine for a year. This
week it started throwing `OutOfMemoryError` partway through, and nothing in
the code changed. How do you investigate?"
*Ideal answer:* First check what *did* change even though "the code didn't"
— input file size/shape (did the upstream system's data volume grow, or did
a data quality issue produce far more distinct orders/customers/products
than usual, inflating the accumulation maps this module's importer builds?),
JVM heap configuration (did a deployment change `-Xmx`?), or concurrent load
(is this job now running alongside something new competing for the same
heap?). If the import logic itself loads the whole file
(`readAllLines`/`readString`), that's an immediate, obvious risk factor to
flag and fix regardless of root cause; if it already streams line-by-line
(like `CsvOrderImporter.importOrders`), the accumulation-side caveat this
module's own Javadoc calls out (a `List<Order>`/lookup-map growing with
distinct-order count, not file size) becomes the next suspect — heap-dump
analysis (which object type dominates retained memory) settles it
definitively rather than guessing.

---

## Export formats (CSV / hand-rolled JSON) and the "why not just Jackson" question

**Beginner:** "Why does this module hand-write a JSON writer instead of
using a library?"
*Ideal answer:* `java-advanced/file-io` has no Maven/Gradle build configured,
so there's no way for plain `javac` to pull in Jackson/Gson from a
dependency — the hand-rolled `MiniJsonWriter` exists purely to demonstrate
the *mechanics* (escaping, quoting, assembling a document) using only the
JDK. It's explicitly not a recommendation for real code — Module 5 (Spring
Boot) uses real Jackson once a build tool exists.
*Follow-up:* "What's a concrete bug `MiniJsonWriter` could produce that
Jackson wouldn't?" → Its escaping doesn't handle full Unicode edge cases
(e.g. lone surrogate pairs) the way a mature library does — an edge-case
input could produce technically-invalid or mis-decoded JSON that a real
library's more thorough implementation would handle correctly.

**Intermediate:** "`InventoryCsvExporter` streams row-by-row; `InventoryJsonExporter`
builds the whole JSON string in memory first. Why the difference, and does
it matter?"
*Ideal answer:* The CSV exporter writes each row directly to the
`BufferedWriter` as it iterates — memory use doesn't grow with catalog
size. The JSON exporter calls `MiniJsonWriter.arrayOf(...)`, which builds
the complete document as one `String` via `StringBuilder` before a single
byte is written. For a catalog of a few hundred products, the difference is
unmeasurable; for a catalog of millions, the JSON path's memory use would
scale with catalog size while the CSV path's wouldn't. It's a legitimate
inconsistency in this module, called out deliberately as an exercise
(EXERCISES.md) — writing the truly streaming version is the best way to
understand why libraries like Jackson expose both a convenience
"build-then-write" API (`ObjectMapper.writeValueAsString`) and a genuinely
streaming one (`JsonGenerator`).

**Senior:** "You inherit a codebase with several hand-rolled JSON-building
helpers scattered across different services, none using a shared library.
What's your assessment and remediation plan?"
*Ideal answer:* Flag it as a real risk, not just a style nitpick — every
hand-rolled JSON builder is a separate opportunity for a subtly different
escaping bug, and any of them handling data that could plausibly contain
attacker-influenced content (not just internal enum-like values) is a
potential injection vector if escaping is even slightly wrong. Prioritize
remediation by which builders touch untrusted/user-supplied data first.
Standardize on one well-maintained library (Jackson is the de facto
standard in the Spring ecosystem) and replace hand-rolled builders
incrementally, starting with the highest-risk ones, rather than a
simultaneous rewrite of everything.

**Scenario:** "A downstream consumer of this module's JSON export reports
that a product name containing an emoji broke their parser. How do you
triage, given what you know about `MiniJsonWriter`'s escaping?"
*Ideal answer:* First confirm whether the emoji is outside the Basic
Multilingual Plane (most modern emoji are, represented in UTF-16 as a
surrogate pair) — `MiniJsonWriter.escape()`'s per-`char` loop processes
UTF-16 code units, not full Unicode code points, so a surrogate pair is
handled as two separate `char`s each individually passing through the
`default` branch unescaped (since both are ≥ 0x20) — likely writing valid
UTF-8 bytes for the pair correctly in this case, but the class's Javadoc
explicitly disclaims "full Unicode escaping edge cases," so this is exactly
the kind of failure mode to expect from a hand-rolled implementation.
Immediate fix: reproduce with the exact input, compare byte-for-byte against
correct output; strategic fix: this is precisely the argument for replacing
`MiniJsonWriter` with a real library the moment a build tool is available
(Module 5), rather than patching this class's Unicode handling further by
hand.

---

## `WatchService`

**Beginner:** "What problem does `WatchService` solve compared to polling a
directory on a timer?"
*Ideal answer:* Polling either wastes CPU (checking too often) or adds
latency (checking too rarely) between a file arriving and it being noticed.
`WatchService` is a thin wrapper over each OS's native file-change
notification mechanism (`inotify` on Linux, `ReadDirectoryChangesW` on
Windows, an FSEvents/kqueue-based approach on macOS) — the OS tells the JVM
when something changes, instead of the JVM repeatedly asking.

**Intermediate:** "What's the most common bug people write with
`WatchService`, and why does it happen?"
*Ideal answer:* Forgetting to call `key.reset()` after processing a key's
events. Without it, that directory silently stops delivering further
events — no exception, no log line, it just goes quiet. It happens because
the code "works" the first time through in casual testing (one file
dropped, one batch of events processed, test ends before anyone notices
nothing further would ever arrive) and the bug only shows up once a second
file is dropped in a longer-running process.
*Follow-up:* "What does `WatchEvent.context()` return, and what's a common
mistake with it?" → A `Path` *relative to the watched directory* (typically
just a filename), not an absolute path — forgetting to
`watchedDir.resolve(event.context())` before opening the file is a very
common first-try bug.

**Senior:** "Would you use `WatchService` to trigger processing of files
landing in a shared network directory mounted via NFS? Why or why not?"
*Ideal answer:* No — native file-change notification facilities generally
don't propagate reliably (or at all, depending on OS/mount configuration)
across NFS and similar network filesystems; `WatchService`'s guarantees
degrade unpredictably in that environment. For a network/shared-storage
trigger, prefer the storage platform's own native event mechanism (for
cloud object storage, event notifications feeding a queue/function) rather
than relying on filesystem-level watching over the network. `WatchService`
is the right tool specifically for a *local* filesystem.
*Follow-up:* "What about `OVERFLOW` events — how would you design around
the possibility that some events get dropped?" → Treat `OVERFLOW` as a
signal to re-scan the directory's current state from scratch rather than
trusting the event stream had complete coverage, and design the downstream
processing to be idempotent against re-processing the same file (since a
re-scan after an overflow could re-trigger something already handled).

**Scenario:** "Your team's file-watcher-based import pipeline has been in
production for months. After a deploy, files stopped being imported, with
no errors in the logs. What do you check first, and what would you change
about the pipeline's design to make this class of failure surface faster
next time?"
*Ideal answer:* First check the obvious `WatchService`-specific culprits:
was `key.reset()` accidentally removed/broken in the deploy (see the
Intermediate answer above)? Did the watched directory itself get
recreated/deleted and re-created (which invalidates the existing watch
registration entirely — `key.reset()` would return `false` in that case,
which the code should be checking and re-registering on, not silently
exiting)? For faster detection next time: add a heartbeat/liveness metric
("last time an event was processed" or "last time the watch loop iterated
at all") with alerting on staleness, since — as this whole scenario
illustrates — a dead `WatchService` fails silently by design (it just stops
producing events), so the *application* needs to actively surface that
silence as an anomaly rather than relying on an exception that will never
come.

---

## ZIP (`java.util.zip`)

**Beginner:** "What's the difference between `ZipOutputStream` and
`ZipFile` in `java.util.zip`?"
*Ideal answer:* `ZipOutputStream` is for *writing* a new archive (wraps an
output stream, one entry written at a time via `putNextEntry`/write/`
closeEntry`). `ZipFile` is for *reading* an existing archive (opens a file
already on disk and lets you enumerate/extract its entries). They're
mirror-image classes for opposite directions, not alternatives for the same
task.

**Intermediate:** "What is 'zip slip,' and when does it matter for code in
this module?"
*Ideal answer:* Zip slip is a path-traversal vulnerability where a
maliciously-crafted ZIP entry name (e.g. containing `../../` segments)
causes naive extraction code to write outside the intended output
directory. It doesn't affect `ExportZipper` as written here, since it only
*creates* archives from trusted, locally-generated filenames — it would
matter the moment code in this codebase *extracts* a ZIP from an untrusted
source (a user upload, a partner delivery), which would need to validate
each entry's resolved path stays within the intended extraction directory
before writing anything.
*Follow-up:* "Show the check you'd add." → Something like:
`Path resolved = outputDir.resolve(entry.getName()).normalize(); if (!resolved.startsWith(outputDir)) { reject/skip the entry; }`
— done *before* opening any output stream to write the entry's contents.

**Senior:** "A nightly job zips several export files together before
uploading them. What would make you choose `STORED` (no compression) over
the default `DEFLATE` for some or all entries?"
*Ideal answer:* If an entry is already compressed data (an image, another
zip, certain binary formats), DEFLATE typically buys little to no size
reduction and adds CPU cost for both compressing and later decompressing —
`STORED` avoids that overhead entirely for those entries while still
getting the "bundle multiple files as one archive" benefit. For genuinely
compressible text (CSV/JSON, this module's actual case), DEFLATE's size
reduction is usually worth the CPU cost unless the job is running somewhere
CPU-constrained and network/storage bandwidth for the larger uncompressed
archive is cheap by comparison — a trade-off to actually measure for the
specific data and environment, not assume.

**Scenario:** "Your service accepts a ZIP file upload from external
partners and extracts it server-side to process the contents. A security
review flags this as high-risk. What do you tell them you've already done,
and what do you still need to add?"
*Ideal answer:* Already-relevant-if-present: file-size limits on the
upload itself and on total extracted size (to prevent a "zip bomb" — a
tiny archive that decompresses to an enormous size, exhausting disk/memory).
Still needed if missing: the zip-slip path-traversal check on every entry
before writing (see Intermediate answer above); a cap on the *number* of
entries (a zip bomb can also be many small entries rather than one huge
one); ideally, extracting into an isolated/temporary location with
restricted permissions rather than directly into a shared/application
directory, so even a successful path-traversal attempt has a much smaller
blast radius.

---

## try-with-resources & suppressed exceptions

**Beginner:** "What does try-with-resources guarantee that a manual
`finally`-block close doesn't?"
*Ideal answer:* Every declared resource's `close()` is called, in reverse
declaration order, no matter how the try body exits — and if `close()`
itself throws while the try body is *also* throwing, the body's exception
still propagates (as the real root cause) with the close() exception
attached as a suppressed exception, rather than one silently replacing the
other. A hand-written `finally` block can get both of those wrong: an
earlier resource's failing `close()` can skip a later resource's `close()`
entirely (a leak), and a close()-time exception can silently overwrite the
original one (lost root cause).

**Intermediate:** "What is a suppressed exception, and how do you inspect
one?"
*Ideal answer:* When a try-with-resources statement's body throws, and one
or more of its resources' `close()` calls also throw during cleanup, the
`close()` exceptions are attached to the primary (body's) exception via
`Throwable.addSuppressed(...)` — done automatically by the JVM, not
something you call yourself. They're retrieved via
`primaryException.getSuppressed()`, which returns a `Throwable[]` (empty if
none). Full stack traces of suppressed exceptions are also included
automatically when a `Throwable` is printed via `printStackTrace()` or
logged through most logging frameworks' exception-formatting, under a
"Suppressed:" heading.

**Senior:** "Should you always add every declared resource type to a single
try-with-resources header, or are there cases you'd nest them instead
(as `ExportZipper` and `ExportFileLocker` both do in this module)?"
*Ideal answer:* Multiple resources in one `try(...)` header is correct when
their lifetimes are genuinely parallel/independent (as
`ResourceHandlingDemo`'s two `FlakyResource`s are). Nest them instead when
one resource's scope should legitimately be narrower than another's — in
`ExportZipper`, each input file's `InputStream` is opened and closed *per
loop iteration* while the archive's `ZipOutputStream` stays open across the
whole loop; putting the per-file stream in the same header as the archive
stream would either not compile (can't declare a resource inside a loop in
a single outer header naturally) or would keep every input file handle open
simultaneously instead of one at a time. `ExportFileLocker` nests a
`FileLock` inside a `FileChannel` specifically because the lock is scoped to
part of the channel's lifetime (released before the channel itself closes),
which is worth making explicit rather than implicit.

**Scenario:** "You're debugging a production incident where logs show only
an `IOException` with message 'disk full,' but the on-call engineer insists
the actual root cause was a downstream validation failure that happened
first. How is this possible, and how would try-with-resources have changed
the outcome?"
*Ideal answer:* This is exactly the failure mode `manualCloseInFinally_WRONG`
demonstrates: if the original code used a manual `finally` block that closes
a resource without protecting the primary exception, a validation failure
(the real root cause) could have been silently overwritten by an IOException
thrown from that `finally` block's `close()` call (e.g. "disk full" during a
buffered writer's flush-on-close) — losing the original exception and its
message/stack trace entirely, leaving only the closing failure visible in
the logs. Rewriting that code with try-with-resources would have kept the
validation failure as the primary, logged exception, with the "disk full"
IOException attached to it as a suppressed exception instead of replacing
it — giving the on-call engineer both pieces of information instead of
losing one.

---

## `FileChannel.lock()`

**Beginner:** "What does `FileChannel.lock()` protect against?"
*Ideal answer:* Another cooperating process (a separate JVM, or a program in
another language) that also calls `lock()`/`tryLock()` on the same file
before writing — it maps to the OS's native advisory file-locking facility.
It does NOT stop a process that ignores locking entirely and just writes to
the file directly.

**Intermediate:** "If two threads in the same JVM both try to
`FileChannel.lock()` overlapping regions of the same file, what happens?"
*Ideal answer:* The second call throws `OverlappingFileLockException`
immediately — it does not block and wait the way `synchronized` or a
`ReentrantLock` would for a second thread. This is a deliberate JDK design
choice, and the practical consequence is that `FileChannel` locking is a
*cross-process* coordination tool, not a substitute for Java-level
in-process thread synchronization.
*Follow-up:* "So how would you protect a single file from being written
concurrently by two threads in the same JVM?" → With ordinary Java
concurrency primitives — `synchronized`, a `ReentrantLock`, or funneling all
writes through a single-threaded executor — not `FileChannel.lock()`,
which is the wrong tool for that specific problem (full treatment in
Module 3).

**Senior:** "Your team is deploying an export job across multiple
containers/pods that could, in principle, run concurrently and could all
mount the same network-attached storage volume. Would `FileChannel.lock()`
give you a safe single-writer guarantee there? Why or why not?"
*Ideal answer:* Not reliably — file-locking semantics over network
filesystems (NFS and similar) are notoriously inconsistent/best-effort
across implementations and configurations, so depending on it as the sole
coordination mechanism across multiple hosts sharing network storage is
risky. For that topology, use a coordination primitive designed for
distributed correctness instead — a database-backed lock/lease, a
distributed lock service, or a cloud storage platform's own
conditional-write/versioning guarantees — and treat any local file locking
as, at best, a secondary safety net, not the primary guarantee.

**Scenario:** "A colleague proposes using `FileChannel.tryLock()` as a
general-purpose 'only one instance of this microservice should run at a
time' mechanism, checked once at application startup. What do you flag?"
*Ideal answer:* The core idea (a lock file as a single-instance guard) is a
legitimate, simple pattern for single-host batch jobs — but flag: (1) it
only protects against another process on the *same machine/filesystem*,
not against the same service being scaled to multiple hosts, which is
increasingly likely for a "microservice" as opposed to a fixed single-box
batch job; (2) if the lock is only checked once at startup and never held
for the life of the process (e.g. released right after the check instead of
kept open), it doesn't actually protect anything — the lock must be held
for as long as "only one instance" needs to be true, typically via keeping
the `FileChannel`/`FileLock` open for the process's whole lifetime, not just
checked and released; (3) if the deployment model might ever move to
multiple hosts/orchestrated containers, a distributed lock/leader-election
mechanism is the more future-proof choice even if a local file lock works
for today's deployment topology.

---

## `Serializable`

**Beginner:** "Why does this module avoid `java.io.Serializable` for its
actual export/import work?"
*Ideal answer:* Three reasons, covered in the code and README: security
(deserializing untrusted `Serializable` data is a known remote-code-execution
risk), versioning (safely evolving a `Serializable` class over time needs
real, sustained discipline most codebases don't maintain), and interop
(serialized bytes are Java-only and JVM/classpath-version-sensitive, while
CSV/JSON can be read by anything).

**Intermediate:** "What does `serialVersionUID` do, and what happens if you
omit it?"
*Ideal answer:* It's an explicit version marker embedded in a class's
serialized form. If omitted, the JVM computes one automatically from the
class's structure at compile time — meaning an unrelated code change (a
new method, a different `javac` version) can silently change the computed
value, making every previously-serialized instance of that class unreadable
(`InvalidClassException`) even though none of the actual *data* changed.
Declaring it explicitly is the bare minimum required to have any control
over that.

**Senior:** "Explain a Java deserialization vulnerability at a level you'd
use in a security-focused interview, without needing to name a specific
CVE."
*Ideal answer:* `ObjectInputStream.readObject()` reconstructs an object
graph by inspecting the byte stream's embedded class names and
instantiating (and populating fields of, and in some cases invoking methods
on, e.g. via custom `readObject`/`readResolve` implementations already
present on arbitrary classes on the classpath) those classes as it goes —
purely as a side effect of "just deserializing." If an attacker controls
the input bytes, they can reference a chain of classes already present on
the target's classpath (a "gadget chain") whose combined
construction/method-invocation side effects, purely as a consequence of
being deserialized, achieve arbitrary code execution — without the
application code ever intending to grant that capability. This is why
deserializing untrusted data with this API is dangerous *regardless* of how
carefully the application's own classes are written — the vulnerability
lives in the combination of "any class on the classpath is a potential
gadget" and "the deserializing code doesn't get to choose which classes are
acceptable" unless it explicitly installs an allow-list filter.
*Follow-up:* "What's the mitigation if you must accept serialized data from
outside your trust boundary?" → `java.io.ObjectInputFilter` (Java 9+,
backported to 8u121+) lets you specify an allow-list of classes/patterns
`readObject()` is permitted to instantiate, rejecting everything else
before it can execute any gadget-chain side effects — but avoiding
`Serializable` for that boundary entirely, in favor of a data-only format,
remains the simpler and safer default.

**Scenario:** "A legacy service you've inherited stores session data as
serialized Java objects in a shared cache, readable by multiple services.
Product wants to add a new field to one of those classes without an outage.
Walk through the risk."
*Ideal answer:* The core risk is exactly the versioning problem this
module's README describes: if the class's `serialVersionUID` isn't pinned
explicitly (or even if it is, if the new field isn't handled carefully),
instances already serialized under the *old* class shape may fail to
deserialize under the *new* shape once the updated code deploys — and
because multiple services read this shared cache, a rolling deployment
could have old and new code reading the same serialized data
simultaneously, multiplying the chance of a mismatch. Safer paths: add the
field as one that tolerates being absent on old data (Java's default
serialization does support adding fields compatibly *if* `serialVersionUID`
stays pinned and the new field has a sensible default when missing from
older bytes) — but a much lower-risk long-term fix is migrating this shared
cache off Java serialization entirely, onto a versioned, cross-service-safe
format (JSON with a schema, or a format with explicit schema evolution
support like Protobuf/Avro), specifically because "multiple services,
independently deployed, sharing serialized data" is precisely the scenario
where `Serializable`'s versioning fragility causes real production
incidents.
