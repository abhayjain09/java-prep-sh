# Module 2 — Line-by-Line Explanation

This walks through every file in
[src/main/java/com/interviewprep/orders/io](src/main/java/com/interviewprep/orders/io)
in the order you should read them (dependencies first). The "why" for each
design choice is also in the inline code comments/Javadoc — this file adds
narrative and connects choices across files. Read alongside
[README.md](README.md), which covers the concepts in teaching-lens depth;
this file focuses on *this specific code*.

## `CsvSupport.java`

```java
public static String toCsvRow(String... fields) {
    StringBuilder row = new StringBuilder();
    for (int i = 0; i < fields.length; i++) {
        if (i > 0) { row.append(','); }
        row.append(escapeField(fields[i]));
    }
    return row.toString();
}
```
A varargs helper (`String...`) so callers can write
`CsvSupport.toCsvRow(sku, name, price, quantity)` without building an array
themselves. Each field is escaped individually before joining — escaping
after joining would be too late (you couldn't tell a comma that was a field
separator from a comma that was part of a field's own text).

```java
public static String escapeField(String value) {
    boolean needsQuoting = value.contains(",") || value.contains("\"")
            || value.contains("\n") || value.contains("\r");
    if (!needsQuoting) { return value; }
    return "\"" + value.replace("\"", "\"\"") + "\"";
}
```
RFC 4180's escaping rule in three lines: wrap in quotes only if a field
contains a comma, a quote, or a newline, and double any quote characters
inside it. Fields that don't need quoting are returned unchanged — both for
readability of the output file, and because some naive downstream CSV
readers get confused by unnecessary quoting even though it's technically
legal.

This class only handles **writing**. Reading (`CsvOrderImporter`) uses a
plain `split(",", -1)` and does not undo this escaping — a deliberate,
documented asymmetry (see the class Javadoc and EXERCISES.md, which assigns
closing that gap as an exercise).

## `CsvOrderImporter.java`

```java
private static final String HEADER = "orderId,customerId,customerName,customerEmail,sku,productName,unitPrice,quantity";
private static final int EXPECTED_FIELDS = 8;
```
The expected shape is defined once, as constants, rather than the magic
number `8` and a literal header string scattered through the method body —
if the CSV shape ever changes, there's exactly one place to update.

```java
try (BufferedReader reader = Files.newBufferedReader(csvFile)) {
    String line;
    while ((line = reader.readLine()) != null) {
```
This is the whole streaming story: `Files.newBufferedReader` opens the file
and wraps it in a `BufferedReader` (an 8 KB internal buffer by default), and
`readLine()` returns exactly one line at a time, `null` at end-of-stream.
The `while ((line = reader.readLine()) != null)` idiom (assignment *inside*
the loop condition) is the standard Java way to write "keep reading until
you hit EOF" — it looks unusual the first time you see it, but it's the
correct, idiomatic form; splitting the assignment and the null-check into
two statements would either duplicate the `readLine()` call or require an
awkward infinite-loop-with-break instead.

```java
if (firstLine) {
    firstLine = false;
    if (line.strip().equalsIgnoreCase(HEADER)) {
        continue;
    }
    // No header present — fall through and treat this first line as data.
}
```
Tolerates both "file has a header" and "file has no header" without two
separate code paths — the first line is checked against the expected
header text, and only *skipped* (not processed as data) if it matches.
Any other first line falls through into the normal per-row parsing below.

```java
String[] fields = line.split(",", -1);
if (fields.length != EXPECTED_FIELDS) {
    System.err.println(...);
    skipped++;
    continue;
}
```
`split(",", -1)` — the `-1` limit argument matters: without it,
`String.split` **drops trailing empty strings** (e.g. `"a,b,,"` would split
into `["a","b"]`, not `["a","b","","",]`), silently shortening the array and
producing a confusing "expected 8 fields, found 6" error even when the row
genuinely had 8 comma-separated positions, the last two just empty. Passing
`-1` preserves trailing empty fields, which is almost always what you want
for fixed-column CSV.

```java
Customer customer = customersById.computeIfAbsent(customerId,
        id -> new Customer(id, customerName, customerEmail));
Product product = productsBySku.computeIfAbsent(sku,
        s -> new Product(s, productName, unitPrice));
Order order = ordersById.computeIfAbsent(orderId, id -> new Order(id, customer));
order.addLine(new OrderLine(product, quantity));
```
Four lines doing a lot of work: `computeIfAbsent` means the *first* row for
a given customer/product/order builds it, and every subsequent row for the
same ID reuses the already-built object rather than constructing (and
re-validating, since `Customer`/`Product` are records with validating
compact constructors) a duplicate. `Order`, being a mutable class with
identity (Module 1's design choice), gets built once per `orderId` and then
has lines appended to it across however many CSV rows share that ID — this
is exactly how a multi-line order survives being represented as multiple
CSV rows.

```java
} catch (RuntimeException e) {
    System.err.println("Skipping invalid CSV line " + lineNumber + ": " + line + " (" + e.getMessage() + ")");
    skipped++;
}
```
Catches `NumberFormatException` (bad price/quantity text) and
`IllegalArgumentException` (a domain record's own validation failing, e.g. a
blank SKU) in one place, logs which line and why, and keeps processing the
rest of the file — a single malformed row doesn't abort an entire batch
import. Note this is a narrow, deliberate `catch (RuntimeException e)`, not
`catch (Exception e)` — it's scoped to exactly the unchecked failures
parsing/construction can throw, not a catch-all that would also swallow
unrelated bugs.

`importOrders_NAIVE_LOADS_WHOLE_FILE` repeats the same per-row logic against
a `List<String>` produced by `Files.readAllLines(csvFile)` up front — read
its Javadoc and README section 2 for why this is the "wrong" contrast, not a
second production code path.

## `InventoryCsvExporter.java`

```java
public static void export(List<Product> catalog, Inventory inventory, Path outputFile) throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
        writer.write(HEADER);
        writer.newLine();
        for (Product product : catalog) {
            int quantityOnHand = inventory.stockOf(product.sku());
            writer.write(CsvSupport.toCsvRow(...));
            writer.newLine();
        }
    }
}
```
`Files.newBufferedWriter` is the write-side mirror of
`Files.newBufferedReader` — same buffering benefit, same try-with-resources
guarantee that the file is flushed and closed even if a `write()` call
throws partway through the loop. `writer.newLine()` (rather than embedding
`"\n"` directly in written strings) writes the *platform-appropriate* line
separator — relevant if this exporter ever ran on Windows, where the native
convention is `\r\n`.

## `MiniJsonWriter.java`

```java
public static String escape(String value) {
    for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        switch (c) {
            case '"' -> out.append("\\\"");
            case '\\' -> out.append("\\\\");
            ...
            default -> {
                if (c < 0x20) { out.append(String.format("\\u%04x", (int) c)); }
                else { out.append(c); }
            }
        }
    }
```
A character-by-character scan building the JSON-escaped form: the two
characters JSON syntax itself requires escaping (`"` and `\`), the common
named escapes (`\n`, `\r`, `\t`), and a generic `\uXXXX` fallback for any
other C0 control character (anything below U+0020) that JSON also requires
be escaped. This is the minimum correct escaping for well-formed input —
see the class Javadoc for what it deliberately does *not* attempt (full
Unicode edge cases like lone surrogates), which is the whole reason this
class isn't a library replacement.

```java
public static <T> String arrayOf(List<T> items, Function<T, String> toJsonObject) {
    StringBuilder out = new StringBuilder();
    out.append('[');
    for (int i = 0; i < items.size(); i++) {
        if (i > 0) { out.append(','); }
        out.append(toJsonObject.apply(items.get(i)));
    }
    out.append(']');
    return out.toString();
}
```
A generic helper: given a `List<T>` and a function turning one `T` into its
JSON object text, builds a JSON array. `InventoryJsonExporter` supplies a
lambda `product -> "{...}"` as that function — the generic method doesn't
need to know anything about `Product`, keeping `MiniJsonWriter` reusable for
any list-of-objects shape.

## `InventoryJsonExporter.java`

```java
String json = MiniJsonWriter.arrayOf(catalog, product -> {
    int quantityOnHand = inventory.stockOf(product.sku());
    return "{" + MiniJsonWriter.field("sku", product.sku()) + "," + ... + "}";
});
```
The lambda passed to `arrayOf` is where `Inventory` and `Product` actually
get turned into JSON text — note it calls `inventory.stockOf(...)` per
product, the same single read-only method `InventoryCsvExporter` uses,
keeping both exporters equally respectful of `Inventory`'s encapsulation
(see README section 3). The entire JSON string is built in memory before
`writer.write(json)` runs — the trade-off called out in README section 3 and
assigned as a streaming-JSON exercise.

## `ExportZipper.java`

```java
try (OutputStream fileOut = Files.newOutputStream(zipFile);
     ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
    for (Path file : filesToZip) {
        zipOut.putNextEntry(new ZipEntry(file.getFileName().toString()));
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            in.transferTo(zipOut);
        }
        zipOut.closeEntry();
    }
}
```
Two nested try-with-resources: the outer one owns the archive's output
stream and the `ZipOutputStream` wrapping it (closed once, after every entry
is written); the inner one owns each input file's stream, opened and closed
per iteration of the loop — deliberately narrow-scoped so at most one input
file handle is open at a time, regardless of how many files are being
zipped. `InputStream.transferTo(OutputStream)` (Java 9+) copies bytes in
chunks internally without the caller managing a manual byte-buffer copy
loop, and without ever holding the whole file's bytes in one `byte[]`.

```java
public static List<String> listEntries(Path zipFile) throws IOException {
    try (ZipFile zf = new ZipFile(zipFile.toFile())) {
        return zf.stream().map(ZipEntry::getName).toList();
    }
}
```
`ZipFile` (reading) is a different class from `ZipOutputStream` (writing) —
both live in `java.util.zip` but serve opposite directions. `ZipFile.stream()`
returns a `Stream<? extends ZipEntry>` over the archive's entries;
`.map(ZipEntry::getName).toList()` (Java 16+ `Stream.toList()`) collects
just the entry names into an immutable `List<String>`, used here purely to
prove `zip()` worked.

## `ResourceHandlingDemo.java`

```java
static final class FlakyResource implements Closeable {
    void use() throws IOException { ... if (failOnUse) throw ...; }
    @Override public void close() throws IOException { ... if (failOnClose) throw ...; }
}
```
A minimal fake resource, configurable to fail on `use()` and/or `close()`
independently — this is what makes it possible to deterministically
demonstrate the exact failure interaction (body throws AND close() throws)
that's otherwise hard to trigger reliably against a *real* resource in a
demo.

```java
public static void manualCloseInFinally_WRONG() {
    FlakyResource resourceA = null;
    FlakyResource resourceB = null;
    try {
        resourceA = new FlakyResource("A", false, true);
        resourceB = new FlakyResource("B", false, false);
        ...
        throw new IllegalStateException("business logic failure inside the try body");
    } catch (IOException | IllegalStateException e) {
        System.out.println("  caught: " + e.getMessage());
    } finally {
        try {
            if (resourceA != null) resourceA.close(); // throws IOException here
            if (resourceB != null) resourceB.close(); // NEVER REACHED
        } catch (IOException e) { ... }
    }
}
```
Walking through what actually happens when this runs: both `use()` calls
succeed, then the explicit `IllegalStateException` fires and is caught,
printing its message. Then `finally` runs: `resourceA.close()` throws
(`failOnClose = true` for A), which is caught by the *inner* `catch
(IOException e)` inside `finally` — but note `resourceB.close()` on the next
line **never executes**, because the exception from `resourceA.close()`
jumped straight past it to the catch block. `resourceB` — despite being a
perfectly healthy resource with `failOnClose = false` — never gets closed.
That's the leak this method exists to make concrete.

```java
public static void tryWithResources_CORRECT() {
    try (FlakyResource resourceA = new FlakyResource("A", false, true);
         FlakyResource resourceB = new FlakyResource("B", false, true)) {
        ...
        throw new IllegalStateException("business logic failure inside the try body");
    } catch (IOException | IllegalStateException e) {
        System.out.println("  caught primary exception: " + e.getMessage());
        for (Throwable suppressed : e.getSuppressed()) {
            System.out.println("    suppressed during resource close: " + suppressed.getMessage());
        }
    }
}
```
Both resources are configured to fail on close this time. Walking through
it: both `use()` calls succeed, the `IllegalStateException` fires, and
**both** `close()` calls are still attempted (in reverse order — B then A)
as the try-with-resources statement unwinds. Both throw `IOException`. The
`IllegalStateException` remains the exception that propagates to the
`catch` (it's the real root cause), and both `IOException`s from the two
`close()` calls are attached to it via `addSuppressed(...)` (done
automatically by the JVM) — retrievable via `getSuppressed()`, which the
catch block loops over and prints. Nothing is lost; contrast this directly
against the previous method's silent `resourceB` leak.

## `ExportFileLocker.java`

```java
try (FileChannel channel = FileChannel.open(file,
        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
    try (FileLock lock = channel.lock()) {
        ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }
}
```
Two nested try-with-resources again, this time both scoped to the *same*
file: the outer one owns the `FileChannel` itself (opened for writing,
creating the file if absent, truncating any previous content); the inner
one owns the `FileLock` acquired on that channel. `channel.lock()` blocks
until the lock is available, covering the whole file by default. The
`while (buffer.hasRemaining())` loop around `channel.write(buffer)` is the
standard defensive pattern for channel writes — a single `write()` call is
allowed to write fewer bytes than the buffer contains (particularly for
non-blocking channels, though less commonly an issue for a plain
`FileChannel`), so looping until the buffer is fully drained is the correct,
portable way to guarantee the whole buffer was written. The lock is released
when the inner try block exits, before the channel itself closes.

## `OrderImportWatcher.java`

```java
try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
    dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
```
`WatchService` is itself `AutoCloseable` — closing it releases the OS-level
watch registration. `Path.register(WatchService, WatchEvent.Kind<?>...)`
is how a directory subscribes to specific event kinds; only `ENTRY_CREATE`
is requested here (this module doesn't care about modifications or
deletions, only new files arriving).

```java
Instant deadline = Instant.now().plus(timeout);
while (true) {
    long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
    if (remainingMillis <= 0) { break; }
    WatchKey key;
    try {
        key = watchService.poll(remainingMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
    }
    if (key == null) { continue; }
```
The bounded-demo shape: a deadline computed once, and a timed `poll()` each
iteration for whatever time remains until that deadline — so the method
returns on its own instead of blocking forever if nothing is ever dropped
into the directory. `key == null` means the poll timed out with no event;
the loop simply re-checks the deadline and tries again with whatever time is
left. The `InterruptedException` handling follows the standard Java
convention: re-set the thread's interrupt flag (`Thread.currentThread().interrupt()`)
rather than swallowing the interruption, so any *outer* code that also
checks interruption status still sees it.

```java
for (WatchEvent<?> event : key.pollEvents()) {
    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
        System.out.println("  WatchService overflow — ...");
        continue;
    }
    @SuppressWarnings("unchecked")
    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
    Path createdFile = dir.resolve(pathEvent.context());
```
`key.pollEvents()` drains every event queued for this key since the last
call (can be more than one). The `OVERFLOW` kind is checked first and
handled specially — its `context()` isn't meaningful the way a normal
event's is. The cast to `WatchEvent<Path>` is safe in practice (this
watcher only ever registers path-producing event kinds) but not something
the type system can verify on its own, hence the explicit, narrowly-scoped
`@SuppressWarnings("unchecked")` with a comment explaining *why* it's safe
— always prefer a scoped, justified suppression like this over a broad one
covering an entire method or class.

```java
List<Order> imported = CsvOrderImporter.importOrders(createdFile);
onBatchImported.accept(imported);
```
This is where `OrderImportWatcher` and `CsvOrderImporter` connect —
each newly detected file is handed straight to the same streaming importer
used for the manual/batch import path in `Main`. There's exactly one CSV
parsing implementation in this module; the watcher doesn't duplicate it.

```java
boolean stillValid = key.reset();
if (!stillValid) { break; }
```
The line most WatchService bugs are actually about (see README section 4
and the diagrams file) — resetting the key is what tells the watch service
"I'm done processing this batch of events, start queuing new ones for me
again." Skipping it silently starves the directory of further events.

## `SerializationDemo.java`

```java
static final class LegacyOrderSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;
    ...
}
```
A throwaway DTO existing only inside this demo — the real domain classes
are never made `Serializable`. The explicit `serialVersionUID` is the bare
minimum discipline for using this API at all (see its Javadoc and README
section 8 for what goes wrong without it).

```java
byte[] bytes;
try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
     ObjectOutputStream objectOut = new ObjectOutputStream(byteOut)) {
    objectOut.writeObject(original);
    objectOut.flush();
    bytes = byteOut.toByteArray();
}
```
`ObjectOutputStream` wraps another stream (here, an in-memory
`ByteArrayOutputStream` instead of a file, so the demo doesn't need to clean
up a temp file just to show serialization mechanics). `flush()` before
`toByteArray()` matters: without it, some bytes could still be sitting in
`ObjectOutputStream`'s internal buffering rather than having been pushed
into `byteOut` yet, and `toByteArray()` would return an incomplete snapshot.

```java
try (ObjectInputStream objectIn = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
    restored = objectIn.readObject();
}
```
The read side: `readObject()` reconstructs the object graph from the byte
stream, and is exactly the method whose behavior on *untrusted* input is the
security concern discussed at length in the class's trailing comments and
README section 8 — this demo only ever deserializes bytes it just produced
itself, which is the one case where that concern doesn't apply.

## `Main.java`

A sequential script exercising every piece above against one shared
workspace directory (created fresh under the OS temp directory on each run,
so re-running never collides with a previous run and nothing is left behind
in the repository). It: writes a sample CSV and imports it (both the
streaming and naive way), exports the same inventory snapshot to CSV and
JSON, zips both exports, runs the try-with-resources vs. manual-close
comparison, writes a file under an exclusive `FileChannel` lock, starts a
background thread that drops a new CSV into the watched directory while the
main thread watches for it via `OrderImportWatcher`, and finally round-trips
a `Serializable` DTO. Every `System.out.println` exists so running it gives
immediate, readable, ordered proof that each piece behaves as its
Javadoc/README section claims — read the console output next to this file.
Note that `Main`'s seven `=== N. ... ===` banners are numbered by
*execution/pipeline order* (import, export, zip, resource-handling demo,
lock, watch, serialize), which is not the same order README.md's sections
use (README groups by concept, e.g. WatchService is its own section 4,
ahead of ZIP and locking, because it's discussed independently of the
export pipeline) — match a banner to its README section by *topic name*,
not by number.
