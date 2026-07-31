# Module 7 — Section-by-Section Explanation

This walks through every file in `sql/` in the order you should read them
(dependencies first — schema before queries, queries before indexes,
indexes before transactions, transactions before partitioning). The "why"
for each design choice also lives inline in the `.sql` files themselves —
this file connects the choices across files and adds narrative.

## `sql/schema.sql`

The five tables map 1:1 onto the Java domain, plus `stock` standing in for
`Inventory`'s internal map (see `diagrams/er-diagram.md` for the full
picture):

- **`customers`** — a direct mirror of `record Customer(id, name, email)`.
  The `PRIMARY KEY (customer_id)` uses the same app-assigned String `id`
  Java already generates, rather than introducing a second,
  database-generated identifier system. The `CHECK (email LIKE '%_@_%')`
  constraint is a deliberately shallow SQL-level echo of `Customer`'s
  compact constructor check (`email.contains("@")`) — real email
  validation still belongs in application code; this is defense in depth,
  not a replacement.

- **`products`** — mirrors `record Product(sku, name, price)`. `price` is
  `NUMERIC(12,2)`, never `FLOAT`/`DOUBLE PRECISION`, for exactly the reason
  `Product.java`'s own Javadoc gives for using `BigDecimal` in Java: binary
  floating point can't represent most decimal fractions exactly, and that
  error compounds across transactions. `NUMERIC` in Postgres is
  Java's `BigDecimal` equivalent — arbitrary-precision exact decimal
  arithmetic.

- **`stock`** — the relational form of `Inventory`'s private
  `Map<String, Integer> stockBySku`. This table exists as its own entity
  specifically *because* SQL has no native "map" column type the way Java
  has `Map` — "a mapping from SKU to quantity" becomes "a table keyed by
  SKU." The `CHECK (quantity_on_hand >= 0)` constraint enforces, at the
  database level, the exact same invariant `Inventory.reserve()` /
  `release()` / `restock()` enforce in Java (`Inventory.java`'s own
  Javadoc: "stock can never go negative") — the difference is that the SQL
  version protects the invariant even against writers that never go
  through the Java `Inventory` class at all (a batch job, a different
  microservice, a manual `UPDATE`). The `version` column is unused by
  `schema.sql` itself but exists to support the optimistic-locking pattern
  demonstrated in `transactions-and-locking.sql`.

- **`orders`** — mirrors `class Order` (`id`, `customer`, `status`,
  derived `totalAmount()`). Three choices are worth re-reading the inline
  comments for if you skimmed them: (1) `order_id` stays `VARCHAR` to match
  `OrderService.placeOrder()`'s app-generated `"ORD-" + sequence` id shape,
  with a commented-out surrogate-`BIGINT`-identity alternative shown for
  contrast; (2) `status` is `VARCHAR` + `CHECK` rather than a native
  Postgres `ENUM` type — see the README's Section 1/6 and the inline
  comment for the full trade-off (mutability of the legal-value set,
  Oracle portability); (3) `total_amount` is a **denormalized cache** of
  the true sum of `order_lines.line_total` — a deliberate
  normalization-vs-performance trade-off, not an oversight, with
  `sample-queries.sql` A2 showing how to detect drift if the two ever
  disagree.

- **`order_lines`** — mirrors `record OrderLine(product, quantity)`, plus
  two things the Java record doesn't need: a surrogate
  `order_line_id BIGINT GENERATED ALWAYS AS IDENTITY` primary key (a
  record has no identity beyond its fields; a database row needs one to be
  individually addressable), and `unit_price`, a **price snapshot** taken
  at order-placement time. That snapshot is the direct SQL realization of
  `OrderLine.java`'s own "PRODUCTION NOTE" Javadoc, which already flags
  that a real system should snapshot the price paid rather than
  re-deriving it from the product's current price. `line_total` is a
  `GENERATED ALWAYS AS (...) STORED` column — the SQL-engine-enforced
  equivalent of `OrderLine.lineTotal()`, guaranteed by Postgres itself to
  never drift from `quantity * unit_price`.

- **What's deliberately absent**: no `CREATE INDEX` statements (kept
  separate in `indexing.sql` on purpose — see that file's closing note on
  why Postgres does *not* auto-index foreign-key columns, only
  primary/unique-key columns).

## `sql/sample-queries.sql`

Four sections, each building on schema.sql, ordered by increasing
complexity:

- **Section A (joins)** — A1 is the "order history" query, an `INNER JOIN`
  across all four tables because every foreign key involved is `NOT NULL`
  (no row can silently disappear). A2 deliberately switches to `LEFT JOIN`
  because the *question* changes — "show me orders whose cached total has
  drifted from the real sum, including ones with zero lines" — and losing
  zero-line orders from an inner join would hide exactly the kind of
  data-quality signal the query is trying to surface. Reading A1 next to
  A2 is the fastest way to internalize "join type follows the question,"
  not "join type is a fixed habit."

- **Section B (subqueries)** — B1 (`NOT EXISTS`) is the answer you should
  give in an interview. B2 (`LEFT JOIN ... WHERE ... IS NULL`) is included
  to show it's logically equivalent and often plans similarly. B3
  (`NOT IN`) is included as a **labeled anti-pattern**, not a
  recommendation — it happens to be safe here only because
  `orders.customer_id` is `NOT NULL`, and the comment explains exactly how
  it silently breaks (returns zero rows for the whole query) the moment
  that assumption stops holding.

- **Section C (CTEs)** — C1 satisfies the brief's minimum bar: one
  non-recursive CTE (`customer_totals`) used in a further query (filtering
  to above-average spenders). C2 chains a second CTE off the first to set
  up ranking, previewing Section D. C3 is the bonus recursive CTE: it
  generates a month-by-month calendar between the earliest and latest
  order using `UNION ALL` with a self-referencing recursive term, so that
  months with zero orders still appear as rows with `orders_placed = 0` —
  something a plain `GROUP BY` can never produce on its own, since it has
  no row to group for a month that has none. This calendar-generation
  pattern is also the conceptual bridge into `partitioning-example.sql`
  (which partitions by the same monthly granularity).

- **Section D (window functions)** — D1 puts `RANK()`, `DENSE_RANK()`, and
  `ROW_NUMBER()` side by side over the identical `ORDER BY` so the tie-handling
  difference between them is directly comparable. D2 computes a running
  revenue total with an explicit `ROWS BETWEEN UNBOUNDED PRECEDING AND
  CURRENT ROW` frame (rather than relying on the implicit default), with a
  comment explaining why `ROWS` rather than `RANGE` matters specifically
  when two orders could share an identical timestamp. D3 uses `LAG()`/
  `LEAD()` with a named `WINDOW w AS (PARTITION BY customer_id ORDER BY
  created_at)` clause to compare each order to the same customer's
  previous/next order — the exact query shape used in production
  fraud/anomaly-detection systems ("is this transaction unusually large
  compared to this customer's history").

## `sql/indexing.sql`

- **Section A** adds indexes on the three foreign-key columns
  (`orders.customer_id`, `order_lines.order_id`, `order_lines.sku`) —
  framed explicitly around the fact that Postgres auto-indexes primary/
  unique keys but never foreign keys, which is the single most likely
  "gotcha" a candidate is expected to know cold.
- **Section B** adds `idx_orders_created_at` (supports Section D's
  ordering/range needs and `partitioning-example.sql`'s partition key) and
  the composite `idx_orders_customer_created_at` — explicitly noting that
  it makes the earlier single-column `idx_orders_customer_id` partially
  redundant, and that a real migration would likely drop the redundant one.
- **Section C** is a short, deliberately non-exhaustive tour of `GIN`
  (full-text/JSONB/array — worked example: trigram/`pg_trgm` search on
  `products.name`) and `GiST` (ranges/geometry/exclusion constraints) — the
  brief's ask was "briefly, for when they'd matter," not full
  implementation depth.
- **Section D** is the worked composite-index column-order example: the
  SAME index `(customer_id, created_at)` demonstrably helps one query
  (filter by customer, sorted by date) and does nothing for another
  (filter by date range alone) — the "leftmost prefix" rule made concrete
  with two contrasting, runnable-looking queries.
- **Section E** is the illustrative `EXPLAIN`/`EXPLAIN ANALYZE`
  before/after — clearly labeled as hand-constructed, not captured — showing
  the `Seq Scan` + `Rows Removed by Filter` signature turning into an
  `Index Scan` + `Index Cond` signature, with the accompanying "how to read
  this" prose a beginner needs the first several times they look at a real
  plan.

## `sql/transactions-and-locking.sql`

This file exists specifically to answer "what does a real database
transaction give you that `OrderService.placeOrder()`'s hand-rolled
try/catch doesn't?" — see the file's own header comment quoting that Java
method directly, and README.md Section 4 for the full narrative answer
(crash-safety via the write-ahead log, not just exception-safety).

- **Section A** — A1 is the happy path (insert order + lines, decrement
  stock, sync the cached total, `COMMIT`). A2 is the failure path: an
  intentionally oversized quantity trips the `CHECK
  (quantity_on_hand >= 0)` constraint, aborting the transaction, and
  `ROLLBACK` undoes every statement since `BEGIN` — no hand-written
  "undo" logic required, unlike the Java catch block's explicit
  `release()` loop.
- **Section B** restates ACID directly against this schema's own
  constraints (Consistency = `schema.sql`'s CHECK/FK/NOT NULL clauses never
  being violated at a commit boundary).
- **Section C** covers all four isolation levels, is explicit that
  Postgres only truly implements three (`READ UNCOMMITTED` behaves exactly
  like `READ COMMITTED` because Postgres's MVCC design makes a genuine
  dirty read structurally impossible regardless of the requested level),
  and gives a concrete two-session timeline for each of the three
  anomalies (dirty read, non-repeatable read, phantom read) directly
  against the `stock`/`orders` tables rather than abstractly.
- **Section D** is the core of the file: D1 is the unguarded race
  (two sessions both read `quantity_on_hand = 5`, both "pass" their
  application-level check, both write, stock ends up oversold) — the exact
  same shape of bug as `Inventory.reserve()`'s documented Java-level race,
  just one layer down (database sessions instead of JVM threads). D2 fixes
  it with `SELECT ... FOR UPDATE` (pessimistic — the second session's
  `SELECT` physically blocks until the first commits). D3 fixes the same
  bug with a `WHERE version = ?` conditional `UPDATE` (optimistic — no
  blocking, but the losing transaction's `UPDATE` silently affects zero
  rows and the application must detect and retry). Both are followed by an
  explicit trade-off comparison (contention level is the deciding factor)
  and a pointer to where this shows up again as Hibernate's `@Version` in
  the future Spring module.
- **Section E** is a short, concrete deadlock example (two sessions locking
  the same two rows in opposite order) and the standard prevention
  strategy (a single, consistent global lock-acquisition order).

## `sql/partitioning-example.sql`

- **Section A** creates `orders_partitioned` as a *separate* demonstration
  table (rather than rewriting `schema.sql`'s `orders` in place) so both
  the "before" and "after" shapes stay visible for direct comparison. The
  single most important thing to notice here is the primary key change
  from `PRIMARY KEY (order_id)` to `PRIMARY KEY (order_id, created_at)` —
  required because Postgres mandates the partition key be part of every
  unique constraint on a partitioned table, and the comment walks through
  the consequence (order_id alone is no longer uniquely enforced by this
  constraint; every referencing foreign key must carry the extra column
  too).
- **Section B** creates three explicit monthly partitions plus a `DEFAULT`
  catch-all, with a note on why the `DEFAULT` partition should stay empty
  in practice (a safety net, not a routing target) and a pointer to
  `pg_partman` as the standard tool for automating partition creation ahead
  of time.
- **Section C** is the "when does this actually help vs. when is it
  needless complexity" discussion the brief asked for, framed as two
  explicit lists (helps / doesn't help) rather than a single blended
  paragraph, so it reads well as interview-answer material directly.

## Reading order recap

`schema.sql` → `sample-queries.sql` → `indexing.sql` →
`transactions-and-locking.sql` → `partitioning-example.sql`, cross-checking
each against `diagrams/er-diagram.md` (structure) and
`diagrams/transaction-locking-flow.md` (the race condition, visually) as
needed. `README.md` is the theory layer above all of this; `EXERCISES.md`
and `INTERVIEW.md` are the practice/assessment layer below it.
