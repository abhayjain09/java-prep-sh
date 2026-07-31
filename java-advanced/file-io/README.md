# Module 2 — File System APIs (`java.io` / `java.nio.file`)

**Domain used throughout:** the same Order/Inventory system from
[java-basics](../../java-basics) (Module 1) — `Customer`, `Product`, `Order`,
`OrderLine`, `Inventory`. This module does not redefine any of those classes;
it imports and reuses them exactly as Module 1 wrote them, from
`com.interviewprep.orders.domain`. What's new here is everything *around*
that domain model that touches the filesystem: bulk-importing orders from a
CSV file, exporting an inventory snapshot to CSV/JSON, watching a directory
for new import files, zipping up a batch of exports, and the resource-safety
and locking concerns that come with all of that.

Companion files:
- [diagrams/csv-import-pipeline.md](diagrams/csv-import-pipeline.md) — sequence diagram of the streaming CSV import
- [diagrams/watch-service-flow.md](diagrams/watch-service-flow.md) — flow diagram of the WatchService event loop
- [src/](src/main/java/com/interviewprep/orders/io) — the actual code
- [EXPLANATION.md](EXPLANATION.md) — section-by-section walkthrough of every file in `src/`
- [EXERCISES.md](EXERCISES.md) — hands-on exercises
- [INTERVIEW.md](INTERVIEW.md) — beginner/intermediate/senior/scenario interview questions with ideal answers

## How to build and run this module

**Environment note:** this module (like the rest of the repo so far) has no
Maven/Gradle wired up — it's built with plain `javac`. It also has a hard
dependency on Module 1's domain classes, so both source roots must be
compiled together in one `javac` invocation (a single classpath, two source
directories):

```bash
cd /path/to/java-prep

# Compile BOTH source roots together — this module imports
# com.interviewprep.orders.domain.* from java-basics rather than redefining it.
javac -d out $(find java-basics/src/main/java java-advanced/file-io/src/main/java -name "*.java")

# Run this module's demo (writes real files to a temp directory and prints
# their paths so you can go open them afterward).
java -cp out com.interviewprep.orders.io.Main
```

If your shell doesn't support `$(...)` command substitution the way the
snippet above assumes (e.g. certain restricted/Windows shells), list the
files explicitly instead, or use `javac -d out @sources.txt` with a text file
listing every `.java` path, one per line.

**What you should see when it runs:** the program creates a temp directory,
prints its path, then walks through all seven numbered sections below in
order — importing a CSV, exporting CSV/JSON, zipping them, demonstrating
try-with-resources vs. manual cleanup, locking a file during export,
auto-importing a file dropped into a watched directory, and round-tripping a
`Serializable` object. Every file it touches is real; the printed workspace
path is where to go look afterward. Nothing here was executed against a real
JDK while writing it (this sandbox has no `java`/`javac` installed) — read
the code carefully, and if something doesn't compile, it's a bug worth
reporting/fixing, not an intentional trick.

---

## 1. `java.io` vs `java.nio.file` — why there are two file APIs

### What it is
`java.io.File` (Java 1.0) represents a file/directory pathname and has a
notoriously bad error-reporting story (most failures return `false` or `-1`
instead of throwing a descriptive exception). `java.nio.file` (Java 7, the
"NIO.2" API) replaced it with `Path` (an immutable pathname, filesystem- and
provider-agnostic) and `Files` (a static-method utility class for every
filesystem operation — read, write, copy, move, walk a tree, watch for
changes, query attributes). This module uses `Path`/`Files` throughout and
`java.io` only where NIO.2 has no equivalent (streams like
`BufferedReader`/`BufferedWriter`, and `Serializable`'s
`ObjectInputStream`/`ObjectOutputStream`).

### Why introduced / problem it solves
`File.delete()` returning `false` tells you *that* the delete failed, not
*why* (permission denied? file doesn't exist? directory not empty? a lock
held by another process?). `Files.delete()` throws a specific, informative
exception (`NoSuchFileException`, `DirectoryNotEmptyException`,
`AccessDeniedException`, ...) — the failure reason is now part of the type
system, not something you have to re-derive by re-checking preconditions
after the fact. NIO.2 also added genuinely new capability `java.io.File`
never had at all: symbolic link support, file attribute views (POSIX
permissions, owner, timestamps), atomic move semantics
(`StandardCopyOption.ATOMIC_MOVE`), and `WatchService` (section 4 below).

### When to use / when not to use
- Use `Path`/`Files` for essentially everything filesystem-related in new
  code — path manipulation, reading/writing files, directory traversal,
  watching for changes, checking existence/permissions.
- Still reach for `java.io` streams (`InputStream`/`OutputStream`/`Reader`/
  `Writer` and their `Buffered*` wrappers) for the actual byte/character
  I/O once a file is open — `Files.newBufferedReader(path)` /
  `Files.newBufferedWriter(path)` are the NIO.2-provided bridge that hands
  you back a perfectly ordinary `java.io.BufferedReader`/`BufferedWriter`,
  which is exactly what `CsvOrderImporter` and `InventoryCsvExporter` do in
  this module. There's no NIO.2-native line-oriented reader/writer type —
  the two APIs are complementary, not competing, once you're past opening
  the file.
- A legacy codebase using `java.io.File` throughout isn't automatically
  "wrong" — `Path` and `File` interconvert cleanly (`file.toPath()`,
  `path.toFile()`), so a gradual migration (touch a method, convert its
  signature) is normal and low-risk. Don't do a big-bang rewrite just to
  use the newer type if the old code is stable and untested.

### Trade-offs & performance implications
- `Files.readAllBytes`/`Files.readAllLines`/`Files.readString` are
  convenient one-liners for small files (config files, small fixtures) but
  — as `CsvOrderImporter`'s naive method demonstrates — load the entire
  result into memory. `Files.lines(path)` returns a lazily-read
  `Stream<String>` instead, but it MUST be closed (it's backed by an open
  file handle) — always wrap it in try-with-resources
  (`try (Stream<String> lines = Files.lines(path)) { ... }`), a detail
  that's easy to miss since most `Stream`s don't hold a resource.
- `Files.walk(path)` (recursively enumerating a directory tree) also
  returns a `Stream` that must be closed for the same reason, and is
  eager about opening file handles as it descends — for very deep/wide
  trees, `Files.walkFileTree` with a `FileVisitor` gives more control over
  memory and lets you prune subtrees early (skip a whole directory without
  descending into it), which `Files.walk` cannot do once the stream exists.

### Enterprise examples
- A batch-processing service migrating from `java.io.File`-based path
  handling to `java.nio.file.Path` specifically to get typed exceptions
  out of failed file operations, replacing `if (!file.delete()) { ... }`
  guesswork with a `catch (NoSuchFileException | AccessDeniedException e)`
  that can log (and alert on) the *actual* reason.

### Common mistakes
- Calling `Files.lines()` or `Files.walk()` without try-with-resources —
  compiles fine, runs fine in a quick test, and leaks a file handle in
  production until file-descriptor exhaustion eventually surfaces as a
  mysterious `"too many open files"` error nowhere near the leaking code.
- Assuming `Path` is inherently safer than `File` for security purposes —
  neither validates that a path stays within an expected directory; see
  the "zip slip" note in section 5 below for a concrete case where that
  matters.

---

## 2. Bulk CSV import — streaming, line-by-line (large-file processing)

### What it is
[`CsvOrderImporter`](src/main/java/com/interviewprep/orders/io/CsvOrderImporter.java)
reads a CSV file where each row is one order *line* (several rows can share
an `orderId` to represent a multi-line order) and builds real `Customer`,
`Product`, `Order`, and `OrderLine` objects from it — reading the file one
line at a time via `BufferedReader.readLine()` inside try-with-resources.

### Why introduced / problem it solves
A naive import (`Files.readAllLines()` or `Files.readString()`) reads the
**entire file** into memory as a `List<String>` or one giant `String` before
processing a single row. For a small file that's invisible. For a real
nightly batch file — a retailer reconciling a day's orders from a legacy
system easily produces a multi-hundred-MB to multi-GB CSV — that's the
difference between a job that scales indefinitely and one that throws
`OutOfMemoryError` in production once the input crosses some file-size
threshold nobody tested against. Streaming line-by-line keeps memory use at
roughly O(1) per line read, independent of total file size (see the "honest
caveat" below for the one place this module's memory bound isn't quite O(1)
overall).

### The wrong vs. correct pair (see the class Javadoc for the full versions)
```java
// WRONG for large files — the whole file is a List<String> in memory
// before the first row is even parsed.
List<String> allLines = Files.readAllLines(csvFile);
for (String line : allLines) { /* parse */ }

// CORRECT — one line resident at a time, regardless of file size.
try (BufferedReader reader = Files.newBufferedReader(csvFile)) {
    String line;
    while ((line = reader.readLine()) != null) { /* parse */ }
}
```

### When to use / when not to use
- Use line-by-line streaming for any file whose size isn't bounded and
  known-small — batch imports, log processing, any file that could
  plausibly grow without a hard cap on record count.
- A one-shot `Files.readAllLines()`/`readString()` is perfectly fine (and
  simpler to write) for genuinely small, bounded files — a config file, a
  small fixture, a file you know is capped at a few KB by its own nature.
  Don't reach for streaming ceremony where it buys nothing.
- `Files.lines(path)` (a lazy `Stream<String>`) is an alternative to the
  `BufferedReader` loop shown here, useful when the rest of the processing
  reads naturally as a stream pipeline (`.filter().map().collect()`).
  It must be closed (see section 1) and, being a `Stream`, is single-use
  and awkward for stateful accumulation across lines (like this importer's
  need to build up multi-line `Order`s) — the imperative loop shown here
  was chosen specifically because the accumulation is inherently stateful.

### Trade-offs & performance implications
- **Honest caveat, called out in the class Javadoc too:** this importer
  still returns `List<Order>` — every parsed `Order` (and small per-file
  `Customer`/`Product` lookup maps) accumulates in memory for the life of
  the call. For a file with a genuinely enormous number of *distinct
  orders* (not just a large file size), that list itself becomes the
  memory bottleneck. The fully-streaming production shape processes and
  discards one completed `Order` at a time via a callback, never holding
  more than a small working set — see EXERCISES.md for that as a follow-on
  exercise. The line-by-line *reading* technique is identical either way;
  only what you do with each parsed record differs.
- `BufferedReader` wraps a small (8 KB by default) internal buffer, so
  `readLine()` isn't making a system call per line — the buffering is what
  makes line-by-line reading fast, not slow. Unbuffered reads
  character-by-character directly against a `FileReader`, by contrast,
  genuinely would be slow (a syscall-ish read per character in the worst
  case) — always wrap raw streams/readers in a `Buffered*` variant.
- This importer's CSV parsing is intentionally naive (a plain
  `String.split(",", -1)`, no quoted-field support) — see
  [`CsvSupport`](src/main/java/com/interviewprep/orders/io/CsvSupport.java)'s
  Javadoc and EXERCISES.md for why, and what a real CSV library
  (Apache Commons CSV, OpenCSV) buys you that hand-rolled splitting doesn't
  (correctly handling embedded commas, quotes, and — critically — quoted
  fields that themselves contain newlines, which a naive line-by-line
  reader like this one cannot handle at all, since it assumes one record
  equals one line).

### Enterprise examples
- Nightly batch reconciliation jobs (orders, payments, inventory feeds)
  ingesting CSV/fixed-width extracts from legacy mainframe or partner
  systems — this exact streaming pattern, scaled up with a message queue
  or bulk database insert instead of an in-memory `List`, is extremely
  common at companies with any batch-oriented back-office processing
  (banks, retailers, insurers).
- A tolerant-reader posture (skip and log a malformed row rather than
  aborting the whole batch) mirrors how real ETL pipelines handle
  imperfect upstream data — abandoning an entire multi-million-row import
  over one bad row is rarely the right trade-off; a bad-row count with
  full logging usually is.

### Common mistakes
- Reading a whole file into memory "just to keep the code simple," without
  ever revisiting that decision as the file's real-world size grows —
  works fine in dev/test against a small fixture, then pages/OOMs in
  production against the actual nightly extract.
- Forgetting that `BufferedReader.readLine()` strips the line terminator —
  code that manually strips a trailing `\n`/`\r\n` again is at best
  redundant and at worst buggy against files with inconsistent line
  endings.
- Assuming a CSV file always has a header row, or always lacks one — a
  reader that hard-codes either assumption breaks the moment an upstream
  system's export format changes slightly; see how `CsvOrderImporter`
  tolerates both.

---

## 3. Exporting an inventory snapshot — CSV and hand-rolled JSON

### What it is
[`InventoryCsvExporter`](src/main/java/com/interviewprep/orders/io/InventoryCsvExporter.java)
and
[`InventoryJsonExporter`](src/main/java/com/interviewprep/orders/io/InventoryJsonExporter.java)
both take the same inputs — a `List<Product>` catalog and an `Inventory` —
and write a point-in-time stock snapshot, one to CSV, one to JSON.

### Why introduced / problem it solves
Downstream systems rarely want your in-process object graph; they want a
file in a format their own tooling already understands. CSV opens directly
in a spreadsheet and is the lowest-common-denominator interchange format for
finance/ops teams; JSON is the default interchange format for services and
web frontends. Exporting the *same* snapshot to both shows that the export
format is a serialization concern layered on top of the domain model, not
baked into it — `Inventory`/`Product` don't know or care that CSV or JSON
exporters exist.

### Why the exporters take a `List<Product>` parameter (an encapsulation callback to Module 1)
`Inventory` (Module 1) deliberately exposes no way to enumerate every SKU
it has ever seen — only `stockOf(sku)`, `reserve`, `release`, `restock`. That
was a deliberate encapsulation choice in Module 1, not an oversight, and this
module respects it rather than widening `Inventory`'s API to suit an export
feature: the exporters accept the catalog to report on explicitly and only
ever call the one read-only method `Inventory` already exposes
(`stockOf`). See `InventoryCsvExporter`'s Javadoc for the full reasoning —
it's the same "ask for what you need, don't demand an object expose its
internals" principle Module 1's README used to justify `Inventory`'s private
backing map in the first place.

### The hand-rolled JSON writer — why it exists, and why it isn't a recommendation
[`MiniJsonWriter`](src/main/java/com/interviewprep/orders/io/MiniJsonWriter.java)
builds JSON with nothing but `String`/`StringBuilder`. **This exists purely
because `java-advanced/file-io` has no build tool configured, so there is no
Jackson/Gson on the classpath for plain `javac` to compile against** — not
because hand-rolling JSON is a good idea. The point of writing it by hand
here is to see the mechanics (quoting, escaping control characters, joining
fields) that a real library does for you, correctly, at scale, with far more
edge-case coverage than four `switch` cases in `MiniJsonWriter.escape()`.
**Module 5 (Spring Boot) uses real Jackson (`ObjectMapper`)** — the moment a
build tool exists, this hand-rolled writer should be deleted, not extended.

### When to use / when not to use
- Export to whatever format the *consumer* of the file actually needs —
  don't pick CSV vs. JSON based on what's easier to write; pick it based
  on what opens the file next (a human in Excel? another service's HTTP
  client? a log-shipping pipeline?).
- Never hand-roll a serialization format in production code that has a
  real library available — the "it's just quotes and commas" instinct is
  exactly what leads to a subtly corrupt export the one time a product
  name contains a comma, a tab, or an emoji outside the Basic Multilingual
  Plane (a case `MiniJsonWriter` does not even attempt to handle
  correctly).

### Trade-offs & performance implications
- `InventoryCsvExporter` streams row-by-row directly to a
  `BufferedWriter` — memory use doesn't grow with catalog size.
  `InventoryJsonExporter`, by contrast, builds the **entire JSON string in
  memory** via `MiniJsonWriter.arrayOf(...)` before writing anything to
  disk — fine for a catalog of dozens/hundreds of products, a real problem
  for a catalog of millions. A genuinely streaming JSON writer would emit
  `[`, then each object followed by `,`, directly to the output `Writer`
  as it iterates, exactly mirroring the CSV exporter's approach — left as
  an exercise (EXERCISES.md) since implementing it is the best way to
  internalize why `Jackson`'s streaming `JsonGenerator` API exists
  alongside its more common `ObjectMapper.writeValue(...)` convenience
  method.
- `BigDecimal.toPlainString()` is used everywhere a price is written out
  (both exporters) instead of `toString()`, specifically to avoid
  scientific notation (`1E+3`) that `BigDecimal.toString()` can produce
  for some internal scales — technically valid in both CSV and JSON, but a
  needless surprise for a human or a naive parser reading the file.

### Enterprise examples
- Nightly inventory/pricing snapshot exports feeding a BI/reporting tool
  (CSV) and a separate real-time dashboard service (JSON) from the exact
  same source data, generated by the exact same job — this dual-format
  pattern (one authoritative read, multiple format-specific writers) is
  standard in data-export pipelines.

### Common mistakes
- Writing JSON (or CSV) by concatenating strings directly against
  user/product data without any escaping — the "works in the demo,
  corrupts the file the first time a name has a special character" bug
  this module's `MiniJsonWriter`/`CsvSupport` explicitly guard against.
- Building the entire output as one in-memory `String`/`StringBuilder` for
  an export whose size isn't actually bounded — exactly the mistake
  `InventoryJsonExporter` makes on purpose, to have something concrete to
  fix in the exercises.

---

## 4. Watching a directory for new import files (`WatchService`)

### What it is
[`OrderImportWatcher`](src/main/java/com/interviewprep/orders/io/OrderImportWatcher.java)
registers interest in `ENTRY_CREATE` events on a directory via
`java.nio.file.WatchService`, and automatically runs `CsvOrderImporter` on
each new file that appears — e.g. an ops process (or an upstream system)
drops `orders-2026-07-31.csv` into an `incoming/` folder and it's imported
without a human running anything or a cron job polling on a timer.

### Why introduced / problem it solves
Before `WatchService` (Java 7), "notice when a new file appears" meant
polling — repeatedly listing a directory's contents and diffing against the
last listing. Poll too often and you waste CPU for no benefit; poll too
rarely and you add latency between a file arriving and it being noticed.
`WatchService` is a thin, uniform wrapper over each OS's *native*
file-change notification facility (`inotify` on Linux, `ReadDirectoryChangesW`
on Windows, an FSEvents/kqueue-based implementation on macOS) — the OS tells
the JVM when something changes, instead of the JVM repeatedly asking.

### When to use / when not to use
- Use `WatchService` for "react to files appearing/changing in a local
  directory" — import-drop folders, config hot-reload, local dev-mode file
  watchers.
- Don't use it across a network filesystem expecting reliable, low-latency
  delivery — NFS/SMB-mounted directories often don't propagate native
  change notifications correctly, silently degrading to unreliable or
  polling-based behavior depending on OS/mount options. For a
  network/cloud storage trigger (e.g. "a file landed in an S3 bucket"),
  use the storage platform's own native event mechanism (S3 event
  notifications → SQS/Lambda, previewed here, detailed in Module 6 — AWS)
  rather than trying to `WatchService` a mounted network path.
- `WatchService` delivery is explicitly **best-effort**, not guaranteed —
  see the `OVERFLOW` handling below. Don't build a feature whose
  correctness depends on every single event being delivered; treat the
  watch as a low-latency *hint* to re-check state, not the sole source of
  truth about what changed.

### Trade-offs & performance implications
- **The `OVERFLOW` event kind**: if events arrive faster than the consumer
  drains them, the OS-level event queue can overflow, and `WatchService`
  delivers a single synthetic `OVERFLOW` event instead of the individual
  events it couldn't buffer. Production code watching a
  high-file-throughput directory should treat `OVERFLOW` as "re-scan the
  directory from scratch" — see `OrderImportWatcher`'s handling.
- **`key.reset()` must be called after processing each key's events, every
  time**, or that directory silently stops delivering further events —
  no exception, no warning, it just goes quiet. This is the single most
  common `WatchService` bug in real code (see
  [diagrams/watch-service-flow.md](diagrams/watch-service-flow.md)) and
  the reason for the "works once in testing, mysteriously stops working"
  bug reports this API tends to generate.
- Only events **after** registration are delivered — a file already
  sitting in the directory when you start watching it produces no event.
  A real "watch this folder starting now" feature needs an explicit
  initial directory scan (`Files.list(dir)`) in addition to registering
  the watch, to pick up anything that arrived before watching started.
- This module's `watchOnce` uses a *timed* `poll()` so the demo terminates
  on its own; a real long-running watcher uses the blocking `take()`
  instead and simply runs for the life of the application (usually on a
  dedicated background thread, since it blocks).

### Enterprise examples
- SFTP/file-drop integration points (a partner or legacy system delivers
  files to a shared directory on a schedule you don't control) commonly
  use exactly this "watch, then process automatically" pattern instead of
  a human triggering the import.

### Common mistakes
- Forgetting `key.reset()` — see above.
- Assuming `WatchEvent.context()` returns an absolute path — it returns a
  path *relative to the watched directory* (typically just the filename);
  forgetting to `dir.resolve(event.context())` before opening the file is
  a very common first-try bug.
- Treating `WatchService` as reliable enough to skip an idempotency check
  on the receiving end — if a process restarts mid-watch, or an `OVERFLOW`
  drops an event, a file can be processed zero times or (after a restart
  that re-registers and re-scans) more than once; import logic should
  tolerate being run twice on the same file without corrupting state
  (e.g. by tracking already-imported filenames, not just reacting to
  events blindly).

---

## 5. Zipping a batch of exports (`java.util.zip`)

### What it is
[`ExportZipper`](src/main/java/com/interviewprep/orders/io/ExportZipper.java)
bundles the CSV and JSON inventory exports into a single `.zip` archive using
`ZipOutputStream` (writing) and reads entries back with `ZipFile` (reading),
both from `java.util.zip`.

### Why introduced / problem it solves
A nightly job commonly produces *several* output files (a CSV, a JSON, maybe
a manifest/log file) that need to travel together — to an SFTP drop, an S3
bucket (Module 6), or an email attachment. Shipping one archive instead of N
loose files is simpler for the receiving side to handle atomically (either
the whole batch arrived or it didn't) and is the standard shape for
"batch of related files" transfer.

### When to use / when not to use
- Use `java.util.zip` directly for straightforward archive creation/reading
  where you don't need anything beyond standard ZIP (which is what this
  module needs). For more advanced needs (password-protected archives,
  split volumes, non-DEFLATE compression methods), a third-party library
  (Apache Commons Compress) is the usual upgrade.
- Streaming each file's bytes directly into the archive
  (`InputStream.transferTo(zipOutputStream)`, as `ExportZipper` does)
  avoids holding a whole file's bytes in a `byte[]` first — reuse the same
  "don't materialize more than necessary" instinct from section 2 here.

### Trade-offs & performance implications
- DEFLATE (ZIP's default compression) trades CPU time for smaller output —
  for already-compressed content (images, other zips) it can even make
  the archive slightly *larger* than the input due to per-entry overhead;
  `ZipOutputStream` supports `STORED` (no compression) per-entry via
  `ZipEntry.setMethod(ZipEntry.STORED)` when that trade-off matters.
- **"Zip slip" — a real, named vulnerability class**: when *extracting* a
  ZIP archive from an untrusted source, an entry name can be crafted with
  path traversal segments (`../../etc/passwd`-style) so that naively
  writing to `outputDir.resolve(entry.getName())` escapes the intended
  extraction directory entirely. This module only *creates* archives
  (from trusted, locally-generated filenames), so it doesn't hit this —
  but any code that *extracts* a ZIP from a network upload or a
  third-party source must validate that
  `outputDir.resolve(entry.getName()).normalize()` still starts with
  `outputDir` before writing, or reject the entry. This is a very common
  senior-level security interview question in exactly this framing.

### Enterprise examples
- Nightly export jobs bundling a data file plus a checksum/manifest file
  into one archive before handing it off to a transfer/delivery mechanism,
  so the receiving system can verify completeness before processing.

### Common mistakes
- Forgetting `zipOutputStream.closeEntry()` between entries (or relying
  solely on the *next* `putNextEntry()` call to implicitly close the
  previous one, which works but is easy to get wrong for the *last*
  entry, which has no "next" call to rely on).
- Extracting a ZIP from an untrusted source without the zip-slip path
  check described above.

---

## 6. try-with-resources vs. manual `close()` — and suppressed exceptions

### What it is
[`ResourceHandlingDemo`](src/main/java/com/interviewprep/orders/io/ResourceHandlingDemo.java)
runs the identical failure scenario (a resource whose `use()` succeeds but
whose `close()` throws, while the calling code also throws its own business
exception) through two implementations: `manualCloseInFinally_WRONG()`
(pre-Java-7 style) and `tryWithResources_CORRECT()` (Java 7+).

### Why introduced / problem it solves
Manually closing resources in a `finally` block has two distinct failure
modes that try-with-resources (Java 7) was introduced specifically to fix:
1. **A resource leak if an earlier `close()` call throws** — in a `finally`
   block that calls `resourceA.close()` then `resourceB.close()`, an
   exception from `resourceA.close()` skips the `resourceB.close()` line
   entirely. `resourceB` never closes.
2. **Losing the real exception** — if the try body throws for a genuine
   business reason, and then a `close()` call *also* throws while cleaning
   up, naive `finally`-block code lets the close() exception silently
   replace the original one, destroying the actual root cause of the
   failure.

try-with-resources guarantees `close()` is called on *every* declared
resource, in reverse declaration order, regardless of how the try body
exits — and if both the body and one or more `close()` calls throw, the
body's exception is the one that propagates, with every `close()` exception
attached to it as a **suppressed exception**
(`Throwable.getSuppressed()`) instead of being lost.

### When to use / when not to use
- Use try-with-resources for every `AutoCloseable`/`Closeable` resource,
  always — there is essentially no case in modern Java where manual
  `finally`-block closing is the better choice. (`WatchService`, streams
  from `Files.lines()`/`Files.walk()`, `FileChannel`, `FileLock`, JDBC
  `Connection`/`Statement`/`ResultSet` — all `AutoCloseable`, all belong
  in a try-with-resources header.)
- The only real judgment call is *what to do in the catch block* when
  there are suppressed exceptions — usually: log the primary exception
  fully, and also log each suppressed one (at least at a lower severity),
  since a suppressed exception can itself indicate a real, separate
  problem (e.g. "the export failed AND the file lock couldn't be
  released") worth knowing about even though it wasn't the primary cause.

### Trade-offs & performance implications
- There's no meaningful performance cost to try-with-resources over manual
  closing — it compiles down to essentially the same bytecode shape
  (nested try/finally with suppression bookkeeping) a careful developer
  would have to hand-write anyway. The "trade-off" is purely that manual
  closing is more code, written correctly less often.
- Resources are closed in **reverse** declaration order
  (`try (A a = ...; B b = ...)` closes `b` before `a`) — this matters when
  resources have a dependency relationship (e.g. `b` was built from `a`
  and needs `a` still open during its own `close()` logic); declare them
  in the order that makes reverse-close correct.

### Enterprise examples
- Any JDBC code (`Connection`, `PreparedStatement`, `ResultSet`, all three
  `AutoCloseable`) is the classic real-world case where pre-Java-7 code
  bases are full of exactly the "nested finally blocks, three levels deep,
  to close three resources safely" boilerplate try-with-resources
  eliminates in one line.

### Common mistakes
- Catching an exception and logging only its message, discarding
  `getSuppressed()` entirely — silently dropping information about a
  secondary failure during cleanup.
- Assuming try-with-resources means you don't need a `catch` block at all
  — it doesn't suppress or swallow the primary exception; it still
  propagates (or must still be caught) exactly as before. try-with-resources
  changes what happens to *close() exceptions*, not whether the body's
  own exception is handled.

---

## 7. `FileChannel.lock()` — protecting a single-writer export

### What it is
[`ExportFileLocker`](src/main/java/com/interviewprep/orders/io/ExportFileLocker.java)
acquires an exclusive `FileLock` via `FileChannel.lock()` before writing an
export file, releasing it (via try-with-resources — `FileLock` is
`AutoCloseable`) once the write completes.

### Why introduced / problem it solves
A nightly export job that might, by scheduling accident or a slow previous
run overlapping the next, execute twice concurrently against the same output
file risks interleaved/corrupted output if both instances write at once.
`FileChannel.lock()` maps onto the OS's native advisory file-locking facility
(`fcntl`/`flock` on POSIX, `LockFileEx` on Windows) so a second cooperating
process attempting the same export waits (or, with `tryLock()`, is told
immediately that someone else already holds the lock) instead of writing
concurrently.

### What this does and does not protect against (the actual interview-relevant nuance)
- **Does protect against:** another well-behaved **process** (a separate
  JVM, or even a program in a different language) that *also* calls
  `lock()`/`tryLock()` on the same file before writing.
- **Does NOT protect against uncooperative writers** — a process that just
  opens the file and writes without checking the lock at all is completely
  unaffected. Advisory locks only stop code that participates.
- **Does NOT protect against multiple threads within the same JVM** — the
  JDK explicitly throws `OverlappingFileLockException` if the *same* JVM
  attempts a second overlapping lock on a file it already holds a lock on,
  rather than blocking the second thread. That means `FileChannel` locking
  is a **cross-process** coordination mechanism, not a substitute for
  Java-level locking (`synchronized`, `ReentrantLock`) **between threads**
  in one process — that's a completely different problem, previewed here
  and given full treatment in Module 3 (Concurrency).
- **Is unreliable over network filesystems** (NFS and some cloud-mounted
  filesystems) — don't depend on it as the sole safety net for a shared
  network export directory in production; use a real distributed
  coordination primitive instead (a database row lock, a lease service,
  a cloud storage conditional write).

### When to use / when not to use
- Use it for exactly the scenario this module demonstrates: guarding
  against accidental concurrent runs of the *same* export job on the
  *same* machine/filesystem, where "another process might also try to
  write this exact file" is a real, if rare, risk.
- Don't use it as your only concurrency-safety mechanism for anything that
  also has in-process multithreading concerns — that needs the Module 3
  tools instead (or in addition).

### Trade-offs & performance implications
- `lock()` blocks the calling thread indefinitely until the lock is
  available — fine for a short export where "wait your turn" is
  acceptable; `tryLock()` (non-blocking, returns `null` if unavailable) is
  the better choice when "skip this run, someone else is already
  exporting" is preferable to blocking.
- Holding a lock for longer than necessary (e.g. across an entire batch
  job instead of just the write itself) needlessly blocks any other
  legitimate exporter — lock as narrowly around the actual protected
  operation as correctness allows, exactly as `ExportFileLocker` does
  (lock acquired immediately before the write, released immediately after).

### Enterprise examples
- Scheduled batch jobs (cron, a job scheduler) using a lock file as a
  cheap "only one instance of this job runs at a time on this box"
  guard — a pattern that predates, and is simpler than, a full distributed
  job-scheduling framework, and is still common for single-host batch
  jobs.

### Common mistakes
- Assuming a `FileLock` acquired in one thread will block a second thread
  in the *same* JVM — it throws `OverlappingFileLockException` instead,
  which surprises people expecting blocking semantics like `synchronized`.
- Relying on file locking as a distributed-systems-grade coordination
  mechanism across multiple machines/a network filesystem, where it's
  known to be unreliable.

---

## 8. `java.io.Serializable` — mechanics, and why this module avoids it

### What it is
[`SerializationDemo`](src/main/java/com/interviewprep/orders/io/SerializationDemo.java)
round-trips a small `Serializable` DTO through `ObjectOutputStream`/
`ObjectInputStream` to show the mechanics, then explains — in the code
comments, summarized here — why none of this module's actual
import/export/zip pipeline uses this approach.

### Why introduced / problem it solves (historically)
`Serializable` (Java 1.1) let any object be turned into a byte stream (and
back) with essentially zero code — implement a marker interface, done. It
was Java's original answer to "persist an object graph" and "send an object
across a network" (RMI). Both use cases now have far better-supported
alternatives (JSON/Protobuf for interchange and persistence; explicit DTOs
mapped by a real serialization library).

### Why this module avoids it (the actual teaching point)
1. **Security.** `ObjectInputStream.readObject()` on bytes from an
   **untrusted** source (a network request, an uploaded file, a queue
   message from outside a trust boundary) is a well-documented
   remote-code-execution vector — "Java deserialization gadget chains"
   were the root cause of several real CVEs in widely-used libraries
   (roughly 2015–2019). Deserializing can be made to instantiate arbitrary
   `Serializable` classes already on the classpath and invoke methods on
   them purely as a side effect — including classes never designed to be
   deserialized this way. A text format parsed by a data-only reader (no
   ability to instantiate arbitrary classes as a side effect of parsing)
   sidesteps this entire vulnerability class. If untrusted deserialization
   via this API is unavoidable, `java.io.ObjectInputFilter` (Java 9+,
   backported to 8u121+) provides an allow-list mechanism — but "don't use
   `Serializable` for untrusted data" is the simpler, safer default.
2. **Versioning.** Evolving a `Serializable` class safely over years of
   changes (adding a field, changing a type) needs real, sustained
   discipline — an explicit `serialVersionUID`, and often hand-written
   `readObject`/`writeObject` methods to bridge old and new forms. Most
   codebases don't maintain that discipline correctly over time. A CSV or
   JSON file is just text: a tolerant reader can ignore an unexpected
   extra field, and there's no hidden JVM-internal format version to fall
   out of sync with the code reading it.
3. **Interop.** Serialized bytes can only be read back by Java, by a
   compatible JVM/classpath. CSV/JSON can be opened by a spreadsheet,
   parsed by a script in any language, or consumed by a completely
   different service — which is exactly why every actual export in this
   module (`InventoryCsvExporter`, `InventoryJsonExporter`) uses a text
   format instead, even though `Serializable` would have been fewer lines
   of code to write.

### When (if ever) `Serializable` is still a reasonable choice
- Short-lived, purely in-process or same-trusted-JVM-fleet use where
  performance genuinely matters and the data never crosses a trust
  boundary (e.g. some internal cache replication, `HttpSession`
  clustering in older application servers). Even then, many teams now
  prefer an explicit binary format (Protobuf, Kryo) with better
  versioning behavior and no built-in "instantiate arbitrary classes"
  attack surface.

### Common mistakes
- Deserializing data from a network request or file upload without an
  `ObjectInputFilter` allow-list (or, better, not using `Serializable` for
  that boundary at all).
- Omitting `serialVersionUID`, then being surprised when a trivial,
  data-unrelated code change (recompiling with a different `javac`,
  adding an unrelated method) breaks deserialization of every
  already-serialized instance in storage.
- Treating "it has fewer lines of code" as a good enough reason to choose
  `Serializable` over a text format for anything that will outlive a
  single process run or ever be read by non-Java code.

---

## Next module

Module 3 — Concurrency (threads, `synchronized`, `java.util.concurrent`,
`ConcurrentHashMap`, executors, Virtual Threads) will fix `Inventory`'s
documented race condition from Module 1 and give full treatment to the
in-process, cross-thread locking this module's `FileChannel.lock()`
section explicitly deferred to it.
