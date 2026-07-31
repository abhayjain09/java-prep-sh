# Module 2 — Exercises

Do these in order — each builds on the previous one's code or ideas. Work
directly in `src/main/java/com/interviewprep/orders/io/`. No test framework
yet (JUnit arrives once Module 5 sets up Maven) — verify each exercise by
extending `Main.java` with a call that prints the result and eyeballing it,
or writing a quick throwaway `System.out.println` check, same as Module 1.
Remember to compile both source roots together (see README.md's build
command) since everything here depends on Module 1's domain classes.

## 1. (Beginner) Make the naive importer prove its own point

`CsvOrderImporter.importOrders_NAIVE_LOADS_WHOLE_FILE` and `importOrders`
produce identical results on the small demo file — the whole point of
README section 2 is that they *don't* behave the same at scale, but nothing
in this module currently demonstrates that difference happening. Write a
small helper (in `Main` or a new throwaway class) that generates a much
larger CSV file on disk (e.g. 200,000 rows, reusing a handful of
products/customers so the resulting `Order` count stays small) and time
both import methods against it using `System.nanoTime()`. You won't likely
see an `OutOfMemoryError` from 200k rows on a modern machine's default heap
— that's fine. Instead, measure and print peak memory using
`Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()`
before and after each import, and compare. **Check yourself:** is the gap
what you expected? What row count (rough order of magnitude) do you think
it would take on your machine's default heap size to make the naive version
actually throw `OutOfMemoryError`, and why is that number hard to predict
precisely?

## 2. (Beginner) Add a quantity/price sanity check with a custom exception

Right now, `CsvOrderImporter` treats a negative or zero `quantity` (which
`OrderLine`'s compact constructor already rejects via
`IllegalArgumentException`) as just another row to skip-and-log. Add a
dedicated check *before* constructing the `OrderLine` that rejects a
`quantity` greater than some business-reasonable maximum (say, 10,000 units
in a single line) by throwing a new checked or unchecked exception of your
choosing — justify which you picked, referencing Module 1's README section
on checked vs. unchecked exceptions. Add a row exceeding your limit to the
sample CSV in `Main` and confirm it gets skipped with a clear log message
distinguishing it from an ordinary malformed-row skip.

## 3. (Intermediate) Teach the importer to read quoted CSV fields

`CsvSupport`'s Javadoc and README section 2 both flag that
`CsvOrderImporter` uses a naive `split(",", -1)` that can't handle a field
containing an embedded comma or a quoted value (e.g. a `productName` of
`"Keyboard, Mechanical"`). Write a `CsvSupport.splitCsvLine(String line)`
method that correctly parses one CSV line honoring RFC 4180 quoting rules
(a field wrapped in `"..."` can contain commas; `""` inside a quoted field
means a literal `"`), and switch `CsvOrderImporter` to use it instead of
`line.split(",", -1)`. Add a row to the sample CSV with a comma inside a
quoted product name and confirm it now parses as one field instead of
splitting incorrectly. **Check yourself:** what happens with your parser if
a quoted field contains an actual newline character — does reading
line-by-line via `BufferedReader.readLine()` even give your parser a chance
to see the whole field? What would have to change about the *reading*
strategy (not just the field-splitting logic) to handle that case
correctly, and why do real CSV libraries not have this limitation?

## 4. (Intermediate) Make `InventoryJsonExporter` actually stream

README section 3 calls out that `InventoryJsonExporter` builds the entire
JSON document as one in-memory `String` via `MiniJsonWriter.arrayOf(...)`
before writing anything, unlike `InventoryCsvExporter`, which writes
row-by-row. Rewrite `InventoryJsonExporter.export(...)` to write directly to
the `BufferedWriter` as it iterates the catalog — writing `[` first, then
each product's JSON object followed by a comma (except before the first
one), then `]` — without ever building the full document as one `String`.
You'll need small `MiniJsonWriter` additions or direct `Writer.write(...)`
calls in place of `StringBuilder` concatenation. Confirm the output file is
byte-for-byte identical to the original implementation's output for the
same input. **Check yourself:** why does this streaming rewrite noticeably
reduce peak memory for a huge catalog, but make almost no difference for
this demo's three-product catalog?

## 5. (Senior) Give `OrderImportWatcher` an initial directory scan

README section 4 notes that `WatchService` only delivers events for files
created *after* registration — a file already sitting in the watched
directory when watching starts is silently ignored forever unless something
else notices it. Add a method
`OrderImportWatcher.scanThenWatch(Path dir, Duration timeout, Consumer<List<Order>> onBatchImported)`
that (a) lists and imports any `.csv` files already present in `dir` before
registering the watch, then (b) proceeds exactly as `watchOnce` does for
anything that arrives afterward. Think carefully about ordering: if you
register the watch *before* finishing the initial scan, a file that arrives
during the scan could either be missed or be double-processed (imported
once by the scan, once by the watch event) depending on exact timing —
pick an approach and justify it in a comment (there's a real, defensible
trade-off here, not a single textbook-correct answer; consider whether
your import path is idempotent — see Exercise 6 below). Update `Main` to use
your new method and confirm `orders-batch-1.csv` (currently ignored by the
watch, per its existing comment) now gets imported too.

## 6. (Senior) Make CSV import idempotent against re-processing

README section 4's "Common mistakes" flags that a file could be processed
more than once (an `OVERFLOW` event followed by a restart-and-rescan, or a
naive combination of Exercise 5's scan-then-watch without careful ordering)
or zero times, and that import logic should tolerate being run twice on the
same file without corrupting state. Currently, running `CsvOrderImporter.importOrders`
twice on the identical file produces two separate `List<Order>` results with
*different* `Order` objects sharing the same `orderId` string but being
different instances — nothing currently detects or prevents that. Design
(and implement) a way to make re-importing the same file a safe no-op the
second time — for example, tracking a set of already-imported file names (or
a hash of file contents) in a small in-memory registry passed into the
importer, and skipping files already seen. Write down, in a comment, what
this approach does and doesn't protect against (e.g. what happens if the
JVM restarts and loses that in-memory registry — is that acceptable for this
use case, and if not, where would a real system persist that tracking
information instead?).

## 7. (Scenario) Design the "batch of exports to S3" pipeline

Product wants this module's nightly inventory export (CSV + JSON, zipped by
`ExportZipper`) uploaded automatically to a cloud storage bucket instead of
sitting in a local `exports/` directory, and wants a *second* job on a
different schedule to react the moment a new export archive appears in that
bucket (mirroring what `OrderImportWatcher` does for a local directory).
Without writing any AWS-specific code yet (that's Module 6's job — this
exercise is about applying what you now know, not jumping ahead), answer in
writing:
- Why is `WatchService` specifically the wrong tool for "react to a new
  file in a cloud bucket," even though it solves the structurally identical
  local-filesystem problem in this module? Name the general category of
  mechanism (not necessarily the exact AWS service) that fills the
  equivalent role in a cloud storage context.
- The `FileChannel.lock()` approach in this module protects against two
  local processes racing to write the same file. If two instances of the
  nightly export job accidentally ran at once and both tried to upload to
  the same cloud object key, what would need to replace `FileChannel.lock()`
  to get an equivalent single-writer guarantee, given that "two separate
  machines/processes with no shared local filesystem" is now the situation
  (not "two threads/processes on one box")?
- The "zip slip" concern from README section 5 was framed around
  *extracting* a ZIP archive received from an untrusted source. If the
  bucket this pipeline uploads to could also receive uploads from other,
  less-trusted systems, and a *different* internal job later downloads and
  extracts whatever archives land there, what would you want that
  extraction code to check before writing any file to disk, and why can't
  you simply trust that "we control the writer" is guaranteed to remain
  true forever?
