# Module 7 — Databases (PostgreSQL & Oracle: SQL, Indexing, Transactions, Partitioning)

**Domain used throughout:** the same Order/Inventory system as every other
module — `Customer`, `Product`, `Order`, `OrderLine`, `Inventory` — now
modeled relationally as `customers`, `products`, `orders`, `order_lines`,
`stock`. Nothing here introduces a new toy example; it's the exact domain
from `java-basics/`, given a schema. See
`java-basics/src/main/java/com/interviewprep/orders/domain/*.java` for the
Java side of every table below.

**ENVIRONMENT CONSTRAINT — read this first:** there is no PostgreSQL,
Oracle, or Docker available in the sandbox this module was written in. Every
`.sql` file here is written and reviewed for syntactic/logical correctness
by hand, but **none of it has been executed against a real database.** Any
`EXPLAIN`/`EXPLAIN ANALYZE` output shown is explicitly labeled
**"ILLUSTRATIVE EXAMPLE OUTPUT"** — hand-constructed to show the *shape* of
a real plan and how to read it, not a captured benchmark. If you want real
numbers, run these files yourself against a local `postgres:16` container
or an RDS instance.

Companion files:
- [diagrams/er-diagram.md](diagrams/er-diagram.md) — entity-relationship diagram of the schema
- [diagrams/transaction-locking-flow.md](diagrams/transaction-locking-flow.md) — sequence diagrams for the stock-decrement race condition, pessimistic fix, and optimistic fix
- [sql/schema.sql](sql/schema.sql) — table definitions, constraints, keys
- [sql/sample-queries.sql](sql/sample-queries.sql) — joins, subqueries, CTEs, window functions
- [sql/indexing.sql](sql/indexing.sql) — indexes for the hot-path queries, B-tree vs GIN/GiST, composite index column order
- [sql/transactions-and-locking.sql](sql/transactions-and-locking.sql) — ACID, isolation levels, pessimistic vs optimistic locking
- [sql/partitioning-example.sql](sql/partitioning-example.sql) — range partitioning `orders` by month
- [EXPLANATION.md](EXPLANATION.md) — section-by-section walkthrough of every `.sql` file
- [EXERCISES.md](EXERCISES.md) — hands-on exercises
- [INTERVIEW.md](INTERVIEW.md) — beginner/intermediate/senior/scenario interview questions with ideal answers

---

## 1. SQL Fundamentals — Schema Design & Query Progression

### What it is
A relational schema expresses the Order/Inventory domain as tables,
columns, and constraints instead of Java objects and references. `SELECT`
with `JOIN` recombines normalized tables back into the shapes an
application actually needs (an order with its customer, lines, and
products all together) — the inverse of how the Java domain composes
objects by reference (`Order` holds `List<OrderLine>`, `OrderLine` holds a
`Product`).

### Why introduced / problem it solves
Java objects live in one process's memory and vanish when it exits.
Relational tables persist data durably, let multiple processes/services
read and write the same data concurrently and consistently (see Section 3,
ACID), and let you ask ad-hoc questions of the data (`SELECT ... GROUP
BY ...`) that were never anticipated as a method on any Java class.
Normalizing into `customers` / `products` / `orders` / `order_lines` /
`stock` (rather than one giant denormalized `orders` table with every
customer and product field repeated on every row) avoids **update
anomalies** — e.g. a customer's email changing would otherwise require
rewriting every historical order row that repeated it, and a typo in one
copy but not another would silently create data inconsistency with no
single source of truth.

### When to use / when not to use
- Normalize (separate tables + foreign keys, as `schema.sql` does) for
  data with clear entity boundaries, referential integrity requirements,
  and multiple independent writers — exactly this domain.
- Denormalize deliberately, and document why, when read performance for a
  specific known-hot query matters more than the small risk of drift —
  `orders.total_amount` is exactly this: a cached, potentially-stale value
  kept for fast reporting, with the trade-off called out explicitly in
  `schema.sql`.
- `NOT EXISTS` (see `sample-queries.sql` Section B) is preferred over `NOT
  IN` whenever the subquery's column could ever be nullable — `NOT IN`
  with even one `NULL` in its subquery's result set silently returns zero
  rows for the *entire* outer query, one of the most common real-world SQL
  bugs.

### Trade-offs & performance implications
- Every `JOIN` costs the planner a decision (nested loop vs hash join vs
  merge join) based on table sizes and available indexes — see Section 2
  for how to read that decision in `EXPLAIN`.
- `LEFT JOIN` vs `INNER JOIN` isn't just a syntax choice — it changes which
  rows survive when there's no match (see `sample-queries.sql` A1 vs A2 for
  a worked comparison of the same domain producing genuinely different
  correct answers depending on the question being asked).
- Generated/computed columns (`order_lines.line_total`) trade a small
  storage and write-time computation cost for a guarantee that the value
  can never drift from its inputs — the database enforces the invariant
  Java's `OrderLine.lineTotal()` computes on demand.

### Enterprise examples
- Every OLTP e-commerce/order platform at scale (retail, banking
  transaction ledgers, trading order books) is built on exactly this
  normalized-tables-plus-joins foundation — the specific column names
  differ, the shape does not.
- Financial services companies (S&P Global, JPMorgan, Goldman Sachs)
  interview SQL heavily and specifically because production incident
  postmortems disproportionately trace back to a subtle `NOT IN` +
  `NULL` bug, a missing join condition producing a fan-out (duplicate
  rows), or a `GROUP BY` that silently dropped a column from the `SELECT`
  list — these are not academic gotchas, they are real recurring
  incidents.

### Common mistakes
- Cartesian-product joins from a missing or wrong join condition —
  multiplies row counts silently rather than erroring, and is easy to miss
  in a result set that "looks about right" until someone notices the totals
  are inflated.
- Using `NOT IN` against a subquery whose column can be `NULL` (see above).
- Forgetting that `COUNT(*)` counts rows including `NULL`s in every column,
  while `COUNT(some_column)` counts only non-`NULL` values of that specific
  column — a frequent source of off-by-some-unknown-amount bugs in
  aggregate reports (see `sample-queries.sql` A2's `COUNT(ol.order_line_id)`
  vs a plain `COUNT(*)` inside a `LEFT JOIN`).

---

## 2. CTEs & Window Functions

### What it is
A **CTE** (`WITH name AS (...)`) is a named, query-scoped temporary result
set — a way to break a complex query into named, sequential, readable
steps, optionally referencing itself (a **recursive CTE**) to walk
hierarchical or iteratively-generated data. A **window function**
(`RANK() OVER (...)`, `SUM() OVER (...)`, `LAG()`/`LEAD()`) computes a value
across a set of rows *related* to the current row (a "window," defined by
`PARTITION BY`/`ORDER BY`) **without collapsing those rows into one output
row**, unlike `GROUP BY`.

### Why introduced / problem it solves
Before window functions, "rank customers by spend" or "show a running
total" required a self-join or a correlated subquery per row — both far
more verbose and typically much slower than the single-pass window-function
equivalent. Recursive CTEs solve a class of problem standard SQL otherwise
cannot express at all: generating a series (a month calendar, as in
`sample-queries.sql` C3) or walking a variable-depth hierarchy (org charts,
bill-of-materials, category trees) with an unknown-in-advance number of
levels.

### When to use / when not to use
- Use a CTE purely for readability when a query has multiple logical
  stages, even if a single flattened query could express the same thing —
  the maintenance benefit of named steps usually outweighs any
  micro-difference in plan shape on Postgres 12+ (see the CTE-inlining note
  below).
- Use `MATERIALIZED` on a CTE deliberately when you WANT Postgres to
  compute it once and reuse the result verbatim across multiple references
  in the outer query, or when you've profiled and found the planner's
  default inlining is choosing a worse plan than materializing would.
- Use window functions instead of a self-join whenever the question is
  "how does this row compare to other rows in the same group" (previous
  order, running total, rank within group) — a self-join can compute the
  same thing but scales quadratically in the worst case and reads far less
  clearly.
- Don't reach for a recursive CTE for a fixed, small, known number of
  levels — a handful of explicit `JOIN`s is more readable than recursion
  when the depth is bounded and known (e.g. "product -> category -> parent
  category," exactly 2 levels, doesn't need recursion).

### Trade-offs & performance implications
- **Postgres 12+**: a CTE referenced exactly once is inlined into the outer
  query by the planner by default (predicates can push down into it, just
  like a subquery) — `AS MATERIALIZED` opts back into the pre-12 behavior
  of always fully computing the CTE first. **Pre-12**: every CTE was an
  "optimization fence," always materialized, which could mean predicates
  from the outer query could NOT be pushed down into the CTE — a real
  historical performance foot-gun that's still relevant when reading old
  blog posts or supporting an old Postgres version.
- Window functions require Postgres to sort/partition the relevant rows in
  memory (or spill to disk if the working set exceeds `work_mem`) —
  multiple window functions sharing an identical `PARTITION BY`/`ORDER BY`
  (via a named `WINDOW` clause, as in `sample-queries.sql` D3) let Postgres
  compute that sort/partition once and reuse it, rather than once per
  window function.
- A recursive CTE with a non-terminating or slow-converging recursive
  member can consume unbounded memory/time — always verify the recursive
  member's `WHERE` clause provably converges (see the safety note in
  `sample-queries.sql` C3).

### Enterprise examples
- Dashboard/reporting queries ("top 10 customers by spend this quarter,"
  "month-over-month revenue growth") are one of the most common real-world
  uses of `RANK()`/`DENSE_RANK()` plus a running-total `SUM() OVER (...)`.
- Fraud/risk systems (relevant at every financial-sector target company
  here) frequently use `LAG()`/`LEAD()` to flag "this transaction is
  unusually large/fast compared to the customer's previous transaction" —
  structurally the same query shape as `sample-queries.sql` D3, applied to
  fraud signals instead of order history.

### Common mistakes
- Assuming a `WHERE` clause can filter on a window function's result
  directly (`WHERE RANK() OVER (...) <= 10` is **not legal SQL** — window
  functions are computed *after* `WHERE`/`GROUP BY`/`HAVING` in logical
  query processing order; you must wrap the windowed query in a subquery or
  CTE and filter in the *outer* query, exactly as `sample-queries.sql` C2
  and D1 do).
- Confusing `RANK()` and `DENSE_RANK()` under ties, and not realizing
  `ROW_NUMBER()` breaks ties arbitrarily unless the `ORDER BY` fully
  disambiguates every row.
- Forgetting the default window frame (`RANGE BETWEEN UNBOUNDED PRECEDING
  AND CURRENT ROW`) behaves differently from `ROWS BETWEEN ...` when there
  are tied `ORDER BY` values — see `sample-queries.sql` D2's explicit
  `ROWS` clause and comment for why that distinction matters for a running
  total specifically.

---

## 3. Indexing & Execution Plans

### What it is
An index is an auxiliary, separately-stored data structure that lets the
database find rows matching a condition without scanning every row in the
table — a B-tree index, conceptually, is a sorted, navigable copy of one or
more columns' values plus a pointer back to the full row. `EXPLAIN`
(and `EXPLAIN ANALYZE`, which actually runs the query and reports real
timings alongside the plan) shows you the sequence of operations
(scan/join/sort/aggregate) the planner chose and, critically, whether an
index was used at all.

### Why introduced / problem it solves
Without an index, `WHERE customer_id = 'CUST-001'` requires a **sequential
scan** — reading every single row in the table to check the condition. On
a 50-row table that's instant; on a 50-million-row table it's the
difference between a sub-millisecond lookup and a query that takes seconds
and holds shared buffer/IO resources the whole time. Indexes trade a small
amount of extra storage and write-time overhead (every `INSERT`/`UPDATE`
must also update every index on the table) for dramatically faster reads
on the indexed columns/patterns.

### When to use / when not to use
- Index foreign-key columns (`orders.customer_id`, `order_lines.order_id`,
  `order_lines.sku`) essentially always — Postgres does **not**
  auto-create these (unlike the primary key/unique index it auto-creates),
  and both join performance and parent-table `DELETE` performance depend on
  them (see `indexing.sql` Section A for the full explanation).
- Index columns that appear in `WHERE`, `JOIN ... ON`, or `ORDER BY`
  clauses of your actual, measured hot-path queries — not every column
  "just in case." Every index adds write overhead on every `INSERT`/
  `UPDATE`/`DELETE` touching that table.
- Use `GIN` for full-text search / JSONB containment / array membership
  (see `indexing.sql` Section C) — a plain B-tree cannot serve
  leading-wildcard `LIKE '%...%'` searches or JSONB `@>` queries
  efficiently at all.
- Use `GiST` for range/geometric types and exclusion constraints (e.g. "no
  two reservations for the same SKU may overlap in time") — again, a case
  a B-tree structurally cannot express.
- Don't add an index defensively on a low-cardinality column (e.g. a
  boolean `is_active` with only two possible values) queried without other
  conditions — the planner will often correctly choose a sequential scan
  over such an index anyway once a meaningful fraction of rows match,
  because random-access index lookups for a large fraction of the table
  cost more than one sequential read of the whole table.

### Trade-offs & performance implications
- **Column order in a composite index matters** — a B-tree index on
  `(customer_id, created_at)` can serve `WHERE customer_id = ?` alone or
  `WHERE customer_id = ? ORDER BY created_at`, but cannot efficiently serve
  a query filtering on `created_at` alone (the "leftmost prefix" rule —
  full worked example with two contrasting queries in `indexing.sql`
  Section D).
- Every index is maintained on every write to the table — a table with ten
  indexes pays that cost ten times per `INSERT`. This is why indexing.sql's
  note about `idx_orders_customer_id` becoming redundant once
  `idx_orders_customer_created_at` exists matters in production: dropping
  overlapping/redundant indexes is a real, valuable maintenance task, not
  just theoretical hygiene.
- `INCLUDE` columns (covering indexes, Postgres 11+) let an index-only scan
  satisfy a query entirely from the index without a second "heap fetch"
  into the actual table row — a further optimization once basic
  leftmost-prefix indexing is in place.
- Reading `EXPLAIN` output: `Seq Scan` + a large `Rows Removed by Filter`
  number is the signature of "this query wants an index that doesn't
  exist yet." `Index Scan`/`Index Only Scan` with a matching `Index Cond`
  is the signature of an index actually being used for the predicate (see
  the illustrative before/after example in `indexing.sql` Section E).

### Enterprise examples
- A production incident where a previously-fast dashboard query suddenly
  takes 30+ seconds after the underlying table grows past some threshold
  is, overwhelmingly often, a missing-index problem exposed by data volume
  — this is one of the single most common "tell me about a performance
  issue you debugged" senior-interview stories, and interviewers listen
  for whether the candidate's diagnosis process included actually running
  `EXPLAIN ANALYZE` rather than guessing.
- Financial trading/reporting systems index heavily on `(instrument_id,
  trade_timestamp)`-shaped composite keys for exactly the same
  leftmost-prefix reasons as `(customer_id, created_at)` here — "give me
  this instrument's trades in this time range" is the dominant query
  shape.

### Common mistakes
- Assuming an index automatically helps every query touching that column —
  it only helps queries whose predicate can use the index's leftmost
  prefix; a query filtering on the *second* column of a composite index
  alone gets no benefit from it.
- Forgetting that a leading wildcard (`LIKE '%foo'` or `LIKE '%foo%'`)
  cannot use a plain B-tree index — only a trailing-wildcard prefix search
  (`LIKE 'foo%'`) can.
- Adding indexes reactively to every slow query without checking for
  redundant/overlapping indexes already present — bloating write latency
  and disk usage without a proportional read benefit.
- Never actually running `EXPLAIN ANALYZE` before and after adding an
  index — "I added an index" is not evidence it helped; the plan and
  timing before/after is.

---

## 4. Transactions, ACID & Locking

### What it is
A **transaction** (`BEGIN` ... `COMMIT`/`ROLLBACK`) groups multiple SQL
statements into one atomic unit — either every statement's effect becomes
permanent, or none does. **ACID** (Atomicity, Consistency, Isolation,
Durability) is the set of guarantees a transactional database makes about
that unit. **Locking** (pessimistic `SELECT ... FOR UPDATE`, or optimistic
`WHERE version = ?`) is how the database (or your application, working with
the database) prevents two concurrent transactions from corrupting shared
state — here, overselling stock by letting two orders both "see" the same
available quantity before either commits.

### Why introduced / problem it solves — direct tie-back to `OrderService.placeOrder()`
`java-basics/src/main/java/com/interviewprep/orders/service/OrderService.java`
hand-rolls exactly this problem at the application level:

```java
try {
    for (OrderLine line : requestedLines) {
        inventory.reserve(line.product().sku(), line.quantity());
        reserved.push(line);
    }
} catch (InsufficientStockException e) {
    for (OrderLine line : reserved) {
        inventory.release(line.product().sku(), line.quantity());
    }
    throw e;
}
```

That code's own comment calls this out directly: it's "a hand-rolled
version of what a database transaction gives you for free." A real
`BEGIN`/`COMMIT`/`ROLLBACK` transaction (see `transactions-and-locking.sql`
Section A) replaces this try/catch-and-manually-undo pattern **and adds a
guarantee the Java version cannot provide on its own**: atomicity across a
**crash**, not just an exception. If the JVM process (or the database
server) dies between two statements inside an uncommitted transaction,
Postgres's write-ahead log guarantees on recovery that the *entire*
transaction is rolled back as if it never started — there is no
partially-applied state to clean up, and no compensating "undo" code had to
be written for it, unlike the Java version's explicit `release()` loop.

### When to use / when not to use
- Wrap any sequence of writes that must succeed or fail together in a
  single transaction — order placement + stock decrement is the canonical
  example (`transactions-and-locking.sql` Section A).
- Use pessimistic locking (`SELECT ... FOR UPDATE`) when contention on the
  *same* row is expected to be common (a single hot-selling SKU) — you'd
  rather serialize cleanly than pay for repeated failed retries.
- Use optimistic locking (a `version` column) when most concurrent
  transactions touch *different* rows and conflicts are rare — avoiding a
  lock on every write improves throughput, at the cost of needing retry
  logic in the application for the occasional conflict.
- Don't wrap slow, unrelated work (an external HTTP call, a report query)
  inside the same transaction as a row lock — every other transaction
  wanting that row queues up behind however long that unrelated work
  takes.
- Don't reach for `SERIALIZABLE` by default — it's the strongest guarantee
  but requires the application to catch serialization-failure errors
  (SQLSTATE `40001`) and retry; most application code is written for
  `READ COMMITTED` (Postgres's default) and doesn't have that retry logic,
  so flipping the isolation level without also adding retry handling
  silently introduces user-facing errors under load.

### Trade-offs & performance implications
- **Isolation level vs concurrency**: stricter isolation (`REPEATABLE
  READ`, `SERIALIZABLE`) prevents more anomalies (see the table in
  `transactions-and-locking.sql` Section C) but increases the chance of a
  transaction being forced to abort and retry under concurrent load —
  there is no free lunch; you're trading correctness guarantees against
  concurrency and retry-handling complexity.
- **Pessimistic locking**: simple to reason about, but a long-held lock
  serializes every other transaction wanting that row, and can deadlock if
  multiple rows are locked in inconsistent orders across code paths (see
  `transactions-and-locking.sql` Section E).
- **Optimistic locking**: no blocking, but under *high* contention on the
  same row, most attempts fail and retry — potentially worse throughput
  than pessimistic locking would have given, the mirror image of
  pessimistic locking's trade-off. This is exactly what JPA/Hibernate's
  `@Version` annotation automates under the hood (Module 5/Spring
  territory) — this raw SQL is what that annotation compiles down to.
- Postgres's MVCC (Multi-Version Concurrency Control) means readers never
  block writers and writers never block readers (each transaction sees a
  consistent snapshot) — but it also means dead row versions accumulate
  and must be reclaimed by `VACUUM`; heavy write/update workloads without
  adequate vacuuming lead to table bloat, a real operational concern in
  its own right (deferred to a caching/performance-tuning discussion
  beyond this module's scope, but worth knowing exists).

### Enterprise examples
- Every order-management, payments, or trading system's "place order /
  reserve inventory / decrement balance" flow is exactly this pattern —
  the stock-decrement race condition shown here is structurally identical
  to a bank account balance race condition, a seat-reservation system, or
  a trading order book matching engine.
- Financial-sector interviewers (S&P Global, JPMorgan, Goldman Sachs)
  specifically probe isolation levels and locking because double-spends,
  double-bookings, and lost updates are the exact class of bug that causes
  real financial loss and regulatory scrutiny — this is not an academic
  topic in that context.

### Common mistakes
- Reading a value, checking it in application code, then writing based on
  that check — with no lock and no version check in between (`stock`'s
  unprotected race in `transactions-and-locking.sql` Section D1) — the
  single most common concurrency bug in CRUD applications, appearing
  identically whether the "concurrency" is two JVM threads (Java-level,
  `Inventory.reserve()`) or two database sessions (SQL-level, shown here).
- Using optimistic locking but forgetting to check "rows affected == 0"
  after the conditional `UPDATE` — this silently drops a lost update with
  no error surfaced anywhere, which is worse than the bug it was meant to
  fix because it fails silently instead of loudly.
- Assuming `REPEATABLE READ` in Postgres behaves like `REPEATABLE READ` in
  every other database — Postgres implements it as snapshot isolation and,
  unusually, also prevents phantom reads at that level (stricter than the
  SQL standard requires); Oracle doesn't offer a `REPEATABLE READ` level
  at all (see Section 6 below).
- Holding a `FOR UPDATE` lock across a network round-trip to an external
  service inside the same transaction — turns a fast lock hold into a slow
  one, hurting every other transaction waiting on that row.

---

## 5. Partitioning

### What it is
Range partitioning splits one logical table into multiple physical tables
("partitions"), each holding rows whose partition-key value falls in a
specific range — here, one partition per calendar month of
`orders.created_at`. Postgres transparently routes reads and writes to the
correct partition; from the application/ORM's point of view, `orders` (or
`orders_partitioned` in this module's demonstration) still looks and
behaves like a single table.

### Why introduced / problem it solves
As `orders` grows into the tens of millions of rows, two problems emerge
that partitioning specifically targets: (1) queries scoped to a recent time
range still have to contend with the planner considering the *entire*
table's statistics and, without "partition pruning," potentially scanning
data far outside the relevant range; (2) bulk lifecycle operations
("delete everything older than our 7-year retention requirement") become
enormous, slow, WAL-heavy `DELETE` statements against a live table instead
of a near-instant `DROP TABLE` of an old partition.

### When to use / when not to use
- Use it once a table is large enough (rule of thumb: tens of millions of
  rows and growing, not a fixed threshold — measure) **and** your real
  query patterns are dominated by the partition key (recent-time-range
  reads, bulk time-based retention/archival).
- Don't use it on tables that will stay small (customers, products in this
  domain) — a good B-tree index already gives efficient range scans at
  that scale, and partitioning adds real schema complexity (see below) for
  no measurable benefit.
- Don't retrofit it onto an existing large table casually — migrating
  requires either a maintenance window or a careful online migration
  (create the partitioned table, backfill in batches, cut over) and a
  composite-primary-key change that ripples into every foreign key
  referencing that table (see `partitioning-example.sql` Section A).

### Trade-offs & performance implications
- **The PostgreSQL partition-key-in-every-unique-constraint rule**: a
  partitioned table's primary key (and any unique constraint) must include
  the partition key. `orders`' natural `PRIMARY KEY (order_id)` becomes
  `PRIMARY KEY (order_id, created_at)` once partitioned — meaning
  `order_id` alone is no longer guaranteed globally unique by that
  constraint, and every foreign key from a child table must carry the
  partition key column too. This is a genuine, non-obvious cost worth
  naming explicitly in an interview.
- Cross-partition queries (aggregating across many months) still work
  correctly but involve combining results from multiple physical tables —
  more moving parts than one table, even though the planner handles it
  transparently.
- Partition *maintenance* (creating next month's partition ahead of time,
  archiving/dropping old ones) is an ongoing operational responsibility —
  typically automated with the `pg_partman` extension or a scheduled job,
  not a "set it and forget it" feature.
- `DROP TABLE` on an old partition is near-instant (a metadata operation)
  compared to a `DELETE` scanning and removing millions of rows — this is
  frequently the single biggest real-world reason companies adopt
  partitioning at all, ahead of the query-performance angle.

### Enterprise examples
- Any table with strong regulatory retention requirements (7-year
  transaction history at a bank, audit logs, trade records at a financial
  data company like S&P Global) partitions by date specifically so
  retention enforcement becomes a scheduled `DROP TABLE` rather than a
  giant recurring `DELETE` job competing for the same resources as live
  traffic.
- Time-series/metrics/event/log tables across virtually every
  observability and analytics platform are partitioned by time for
  identical pruning and lifecycle reasons.

### Common mistakes
- Choosing a partition key that doesn't match actual query patterns (e.g.
  partitioning `orders` by month but then querying almost exclusively by
  `customer_id` across all history) — you get the lifecycle-management
  benefit but little to no query-performance benefit.
- Forgetting to provision future partitions ahead of time, discovering the
  gap only when an `INSERT` for next month fails (or silently lands in an
  unintended `DEFAULT` partition if one exists) — see
  `partitioning-example.sql` Section B's note on why the `DEFAULT`
  partition should ideally stay empty and act only as a safety net.
- Assuming partitioning is a performance feature you should reach for
  "because it scales better" on a table that's nowhere near the size where
  it would matter — the added schema/operational complexity is a real,
  ongoing cost, not a free upgrade.

---

## 6. PostgreSQL vs Oracle — What Actually Differs for Interviews

Both are used across the target companies in this repo's scope (many
enterprises, including large banks, run Oracle for legacy core systems and
Postgres for newer services) — interviewers expect you to know the
*conceptual* differences even if your daily driver is one or the other.

| Topic | PostgreSQL | Oracle | What to say in an interview |
|---|---|---|---|
| **Auto-incrementing keys** | `GENERATED ALWAYS AS IDENTITY` (SQL-standard, PG 10+); older `SERIAL` pseudo-type is sequence + default sugar | `GENERATED ALWAYS AS IDENTITY` since Oracle 12c; before that, a `SEQUENCE` + a `BEFORE INSERT` trigger (or `sequence.NEXTVAL` in the `INSERT` itself) was the *only* way | Know that pre-12c Oracle codebases almost always hand-roll sequence+trigger — recognizing that pattern in legacy code and explaining why IDENTITY is strictly better (one less trigger to maintain, no risk of forgetting it) is a good signal. |
| **Row limiting** | `LIMIT n OFFSET m` (also supports the SQL-standard `FETCH FIRST n ROWS ONLY`) | Historically `ROWNUM <= n` in the `WHERE` clause (a pseudo-column assigned *before* `ORDER BY` is applied — a classic Oracle gotcha: `WHERE ROWNUM <= 10 ORDER BY x` does NOT give you the top 10 by `x`, it gives you an arbitrary 10 rows, THEN sorts those); Oracle 12c+ adds standard `FETCH FIRST n ROWS ONLY` | The `ROWNUM`-before-`ORDER BY` trap is a genuinely common real bug in older Oracle codebases and a strong interview question — know to wrap the ordered query in a subquery *then* apply `ROWNUM` outside it (or just use `FETCH FIRST` on 12c+). |
| **Procedural SQL** | PL/pgSQL — Ada/Pascal-flavored, embedded in `CREATE FUNCTION`/`CREATE PROCEDURE` (procedures since PG 11) | PL/SQL — Oracle's own, more mature/feature-rich procedural extension, deeply integrated with packages, cursors, and Oracle-specific tooling | PL/SQL is generally considered more mature and Oracle-specific tooling (packages, autonomous transactions) doesn't map 1:1 to PL/pgSQL — don't assume code ports directly between the two without rework. |
| **MVCC implementation** | Old row versions kept in the *same table* (heap), reclaimed by `VACUUM`; unvacuumed bloat is a real, visible operational concern (`pg_stat_user_tables`, autovacuum tuning) | Old row versions kept in separate **undo segments**/**rollback segments**, reconstructed on demand for consistent reads; `ORA-01555 snapshot too old` is Oracle's version of "your undo retention wasn't long enough for a long-running query" | Both are MVCC (readers don't block writers), but the *mechanism* differs enough that the failure modes differ: Postgres bloats the live table if under-vacuumed; Oracle can fail a long-running query outright with `ORA-01555` if undo space is reused too soon. |
| **Native ENUM type** | `CREATE TYPE ... AS ENUM (...)` exists but is append-only (can't remove/reorder values) — this module's `orders.status` deliberately uses `VARCHAR` + `CHECK` instead (see `schema.sql`'s inline discussion) | No native enum type at all — Oracle models this the same way, `VARCHAR2` + `CHECK` constraint, or a lookup table with a foreign key | This means the VARCHAR+CHECK schema in this module needs **zero changes** to run on Oracle (aside from Oracle's `VARCHAR2` naming) — a genuinely portable design choice, worth stating explicitly if asked "would this schema work on Oracle too?" |
| **Isolation levels** | `READ COMMITTED` (default), `REPEATABLE READ` (snapshot isolation, also prevents phantoms — stricter than the SQL standard requires at that name), `SERIALIZABLE` (true serializable snapshot isolation, SSI) | `READ COMMITTED` (default), `SERIALIZABLE` (Oracle's `SERIALIZABLE` is actually implemented as snapshot isolation, not textbook conflict-serializable execution — so it doesn't prevent every anomaly a from-first-principles reading of "serializable" would suggest) | Oracle has **no** `REPEATABLE READ` level at all — if asked to compare, say so directly rather than assuming feature parity by name. |
| **String types** | `VARCHAR(n)`/`TEXT` — `TEXT` (unbounded) is idiomatic and has no meaningful performance penalty vs a bounded `VARCHAR(n)` in Postgres | `VARCHAR2(n)` (note the "2" — plain `VARCHAR` is a reserved-but-deprecated legacy type, always use `VARCHAR2`); Oracle historically also treats an empty string `''` as `NULL` for `VARCHAR2`, which Postgres does **not** — a real portability trap | `'' IS NULL` being `TRUE` in Oracle but `FALSE` in Postgres has broken real cross-database migrations — worth knowing as a "gotcha" answer. |
| **`RETURNING` clause** | `INSERT/UPDATE/DELETE ... RETURNING col` — very idiomatic, commonly used to get a generated ID back without a second round-trip | `INSERT ... RETURNING col INTO :variable` exists but is PL/SQL-only syntax (bind-variable target), not usable the same way from a plain client-side SQL statement | Know the plain-SQL vs PL/SQL-only distinction if asked to port a query fetching a generated key back to the caller. |

**One-line summary for an interview:** "Both are mature, ACID-compliant,
MVCC relational databases with the same core SQL — the differences that
actually bite in practice are identity/sequence generation syntax,
`ROWNUM`-before-`ORDER BY` in older Oracle row-limiting code, no native
`REPEATABLE READ` in Oracle, and undo-segment vs in-heap MVCC changing the
specific failure mode you see under a long-running transaction (`ORA-01555`
vs table bloat)."

---

## Next module

This module is self-contained within the repo's `database/` folder scope.
Later modules (`spring/`, Module 5's Spring Data JPA layer in particular)
will map this exact schema onto JPA entities and show how `@Version`
(optimistic locking) and `@Transactional` compile down to the raw SQL
patterns demonstrated here.
