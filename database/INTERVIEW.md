# Module 7 — Interview Questions

Organized by topic, then by level (beginner → intermediate → senior →
scenario). Each includes an ideal answer outline and likely follow-ups.

**A note on weighting:** SQL is weighted unusually heavily at
finance-sector companies specifically — S&P Global, JPMorgan, and Goldman
Sachs all run technical screens with a dedicated, often live-coding SQL
round, separate from general coding/system-design rounds, because so much
of their core infrastructure (trading, risk, settlement, reference data,
market data) is fundamentally relational and transactional. Broader tech
companies (Amazon, Microsoft, Google, Oracle, Adobe, Salesforce, Atlassian)
tend to fold SQL into a data/backend round rather than giving it a
dedicated slot, but still expect fluency, especially for a "full stack"
title. Don't under-prepare this module relative to Java/Spring — at a bank,
it can be the deciding round.

---

## SQL Fundamentals (Joins, Subqueries)

**Beginner:** "What's the difference between `INNER JOIN` and `LEFT
JOIN`, and when would you use each?"
*Ideal answer:* `INNER JOIN` returns only rows with a match on both sides;
`LEFT JOIN` returns every row from the left table, with `NULL`s filling in
for unmatched right-side columns. Use `INNER JOIN` when a match is
guaranteed or required for the row to be meaningful (see
`sample-queries.sql` A1 — every order line has a valid order/product by
constraint). Use `LEFT JOIN` when you specifically want to see rows
*without* a match — `sample-queries.sql` A2 uses it to surface orders with
zero lines, which an `INNER JOIN` would silently hide.
*Follow-up:* "If you `LEFT JOIN` and then put a condition on the right
table's column in the `WHERE` clause instead of the `ON` clause, what
happens?" → It silently turns the `LEFT JOIN` back into behaving like an
`INNER JOIN` for that condition, because `WHERE` filters *after* the join,
and `NULL`s from unmatched rows fail most conditions — a very common real
bug.

**Intermediate:** "Why is `NOT IN` dangerous with subqueries, and what
should you use instead?"
*Ideal answer:* if the subquery's column can contain even one `NULL`,
`x NOT IN (1, 2, NULL)` evaluates to `UNKNOWN` for every `x` (SQL's
three-valued logic), which means the entire outer `WHERE NOT IN (...)`
returns zero rows — not "all rows except the ones in the subquery." Use
`NOT EXISTS` instead (see `sample-queries.sql` Section B), which has no
such trap because it's checking row existence, not comparing against a
value list that could contain `NULL`.
*Follow-up:* "Is `NOT IN` ever actually safe to use?" → Yes, when the
subquery's column is provably `NOT NULL` (as `orders.customer_id` is in
this schema) — but `NOT EXISTS` is the safer default habit regardless,
since schema changes later (making a column nullable) can silently
reintroduce the bug into code that "used to be fine."

**Senior:** "A junior engineer's report shows revenue numbers roughly
double what finance's independent numbers show. Walk through how you'd
debug this from the SQL side."
*Ideal answer:* the single most common cause of silently-inflated
aggregates is a join that fans out — e.g. joining `orders` to
`order_lines` and then summing `orders.total_amount` (not
`order_lines.line_total`) per row, so an order with 2 lines counts its
total_amount twice. Diagnosis approach: check row counts before and after
each join in the query (`SELECT COUNT(*)` at each stage) against expected
cardinality, look specifically for aggregates computed on a
denormalized/parent-level column post-join rather than the
child-level column, and verify with a manual spot-check on one order.
*Follow-up:* "How would you prevent this class of bug from recurring?" →
Code review checklist item for "aggregate function applied to a column
from the 'one' side of a one-to-many join," prefer computing per-entity
aggregates in a CTE *before* joining wider (exactly `sample-queries.sql`
C1's shape) rather than aggregating after a wide join.

**Scenario:** "Design the query (or set of queries) to find, for each
customer, whether their most recent order was larger or smaller than
their average order size — without a stored procedure, in one query."
*Ideal answer:* a window-function query using `AVG() OVER (PARTITION BY
customer_id)` for the per-customer average alongside `LAST_VALUE()` or a
`ROW_NUMBER() = 1` (ordered by `created_at DESC`) filter for "most recent,"
in a CTE, then a comparison in the outer query — structurally identical to
`sample-queries.sql` D1/D3 combined. This is a good moment to demonstrate
window functions are the standard modern answer to "compare this row to an
aggregate or another row in the same group" rather than a self-join or
correlated subquery.

---

## CTEs & Window Functions

**Beginner:** "What's the difference between a CTE and a subquery?"
*Ideal answer:* Functionally, in modern Postgres (12+), often nothing —
a singly-referenced CTE is inlined into the plan just like a subquery
would be. The real difference is readability/structure: a CTE gives a
name to an intermediate result and lets a complex query be read as
sequential, named steps (see `sample-queries.sql` C1/C2), and lets you
reference the SAME intermediate result multiple times without repeating
its text — a subquery would need to be duplicated or wrapped differently
to be reused.
*Follow-up:* "Are CTEs always materialized (computed once, then reused),
even if referenced only once?" → No, not since Postgres 12 — see the
`MATERIALIZED`/inlining discussion in `README.md` Section 2 and
`sample-queries.sql` C1's comment.

**Intermediate:** "Explain `RANK()` vs `DENSE_RANK()` vs `ROW_NUMBER()`
with an example involving ties."
*Ideal answer:* given spend values [100, 90, 90, 80]: `RANK()` gives
[1, 2, 2, 4] (ties share a rank, the next rank skips ahead by the number of
tied rows); `DENSE_RANK()` gives [1, 2, 2, 3] (ties share a rank, no gap
afterward); `ROW_NUMBER()` gives [1, 2, 3, 4] (never ties, arbitrarily
breaks them unless the `ORDER BY` fully disambiguates). See
`sample-queries.sql` D1 for all three computed side by side over the same
data.
*Follow-up:* "Why can't you write `WHERE RANK() OVER (...) <= 10`
directly?" → Window functions are evaluated after `WHERE`/`GROUP BY`/
`HAVING` in SQL's logical processing order, so `WHERE` can't reference
them — you must wrap the windowed `SELECT` in a subquery/CTE and filter in
the outer query, as done throughout `sample-queries.sql` Section D.

**Senior:** "When would a recursive CTE be the right tool, and what's the
biggest risk in writing one?"
*Ideal answer:* when the depth of traversal isn't known in advance and a
fixed number of `JOIN`s can't express it — org charts/manager hierarchies,
bill-of-materials explosions, category trees, or (as in
`sample-queries.sql` C3) generating a series (a month calendar) with no
source table to `SELECT FROM` at all. Biggest risk: a recursive member
whose termination condition doesn't provably converge causes unbounded
recursion — runs until memory/time limits are hit rather than erroring
cleanly up front. Always verify the recursive member strictly progresses
toward the termination predicate.
*Follow-up:* "How would you protect against a runaway recursive CTE in
production?" → A provably-converging predicate is the real fix, but as a
belt-and-suspenders safety net, some teams add an explicit depth counter
column incremented each recursive step with a `WHERE depth < N` cap.

**Scenario:** "Product wants a report: 'for every customer, list their
top 3 orders by value, and for each, how it ranks against that same
customer's OTHER orders from the prior 90 days.' Sketch the query
structure."
*Ideal answer:* nested/partitioned window functions — a `PARTITION BY
customer_id` window for the per-customer rank (`DENSE_RANK() OVER
(PARTITION BY customer_id ORDER BY total_amount DESC)`), likely inside a
CTE, filtered to `<= 3` in an outer query (can't filter on the window
result directly — same rule as above), combined with a date-windowed join
or a second window function scoped by a rolling frame for the 90-day
comparison. The key insight to state out loud: partitioned window
functions are what make "top N *per group*" queries clean, versus a much
messier correlated subquery per customer.

---

## Indexing & Execution Plans

**Beginner:** "What is a database index, in your own words, and what's
the trade-off of adding one?"
*Ideal answer:* a separate, sorted structure (usually a B-tree) that lets
the database find matching rows without scanning the whole table — trading
extra storage and slower writes (every index must be updated on every
`INSERT`/`UPDATE`/`DELETE` touching that table) for much faster reads on
the indexed pattern.
*Follow-up:* "Does PostgreSQL automatically index foreign key columns?" →
No — it auto-indexes primary keys and unique constraints (needed to
enforce those constraints), but NOT plain foreign keys, which is why
`indexing.sql` Section A explicitly adds indexes on `orders.customer_id`,
`order_lines.order_id`, and `order_lines.sku`.

**Intermediate:** "Explain the 'leftmost prefix' rule for composite
indexes with a concrete example."
*Ideal answer:* a composite B-tree index on `(A, B)` is physically sorted
by `A` first, then by `B` within each `A` value — like a phone book sorted
by last name then first name. It efficiently serves `WHERE A = ?`,
`WHERE A = ? AND B = ?`, and `WHERE A = ? ORDER BY B`, but NOT `WHERE B = ?`
alone or `ORDER BY B` alone across all `A` values. Concretely:
`indexing.sql`'s `idx_orders_customer_created_at (customer_id,
created_at)` serves "this customer's orders, most recent first" well, but
does nothing for "all orders in January" (filtering on `created_at`
alone) — that needs the separate `idx_orders_created_at` single-column
index, or a differently-ordered composite index.
*Follow-up:* "So should you always add indexes in both column orders to be
safe?" → Only if you actually have hot queries needing both orders — every
extra index is pure write-overhead if unused; check `pg_stat_user_indexes`
for actual usage before keeping (or removing) an index in production.

**Senior:** "Walk me through how you'd diagnose a query that used to run
in milliseconds and now takes 20 seconds, with nothing in the code having
changed."
*Ideal answer:* run `EXPLAIN (ANALYZE, BUFFERS)` first, not last — compare
the actual plan against what you'd expect. Look for a `Seq Scan` where an
`Index Scan` used to appear (see `indexing.sql` Section E's illustrative
before/after) — a common cause with "nothing in the code changed" is
`ANALYZE` staleness (the planner's row-count statistics no longer reflect
reality after significant growth or a bulk load, causing it to
mis-estimate and choose a worse plan) or table bloat from insufficient
`VACUUM`ing inflating the apparent table size. Also check whether the
table simply crossed a threshold where an existing index selectivity
assumption stopped holding (an index that was a great choice at 10,000
rows can become a poor one at 50 million if the underlying data
distribution changed).
*Follow-up:* "What would make you suspect a lock/blocking issue instead of
a plan issue?" → If `EXPLAIN ANALYZE`'s reported execution time is fast but
the query "feels" slow from the application, the time is likely being
spent WAITING for a lock, not executing — check `pg_locks`/
`pg_stat_activity` for blocked/blocking sessions rather than the plan
itself.

**Scenario:** "You're asked to speed up 'full-text search on product
names' — `WHERE name ILIKE '%wireless%'` currently takes several seconds
on a few million products. What do you do?"
*Ideal answer:* a plain B-tree index cannot serve a leading-wildcard
`LIKE`/`ILIKE` pattern at all (it can only use a B-tree for a *prefix*
match, `LIKE 'wireless%'`). The fix is a `GIN` index with the `pg_trgm`
(trigram) extension for fuzzy substring matching, or Postgres's native
`tsvector`/`tsquery` full-text search with a `GIN` index for true
linguistic search (stemming, ranking) — both covered in `indexing.sql`
Section C. Name the specific extension/approach, not just "add an index."

---

## Transactions, ACID & Locking

**Beginner:** "What does ACID stand for, and give a one-sentence example
of each from the Order/Inventory schema."
*Ideal answer:* Atomicity — placing an order and decrementing stock either
both happen or neither does (`transactions-and-locking.sql` Section A).
Consistency — a transaction can never leave `stock.quantity_on_hand`
negative, because the `CHECK` constraint refuses it. Isolation — two
concurrent order placements don't see each other's uncommitted writes
(degree depends on isolation level — see Section C). Durability — once
`COMMIT` returns, the order survives even if the database crashes a moment
later.

**Intermediate:** "`OrderService.placeOrder()` in the Java code uses a
try/catch with a manual rollback loop instead of a database transaction.
What exactly does a real transaction give you that hand-written code
doesn't?"
*Ideal answer:* the hand-rolled version only protects against a caught
*exception* mid-loop — it has no answer for the JVM process or the
database server crashing between two `reserve()` calls. A real
`BEGIN`/`COMMIT` transaction is atomic across a crash too, guaranteed by
the database's write-ahead log: on recovery, an uncommitted transaction is
rolled back in its entirety, with zero custom "undo" code required for any
specific statement type. This is the central point of
`transactions-and-locking.sql`'s header comment and `README.md` Section 4
— be ready to state it precisely, since "it's basically the same as
try/catch" is the wrong answer and a common one.
*Follow-up:* "Once this code is ported to Spring, what annotation
typically replaces this whole pattern?" → `@Transactional` — previewed
here, detailed in the future Spring module; worth mentioning you understand
it's a declarative wrapper around exactly this `BEGIN`/`COMMIT`/`ROLLBACK`
machinery, not magic.

**Senior:** "Explain the four SQL isolation levels and which specific
anomaly each prevents, then say which ones PostgreSQL actually implements
distinctly."
*Ideal answer:* Read Uncommitted (allows dirty reads, non-repeatable
reads, phantom reads), Read Committed (prevents dirty reads only),
Repeatable Read (also prevents non-repeatable reads), Serializable
(prevents all three, including phantoms). PostgreSQL only implements THREE
distinct behaviors — `READ UNCOMMITTED` is accepted as valid syntax but
behaves identically to `READ COMMITTED`, because Postgres's MVCC design
makes a true dirty read structurally impossible at any level. Also:
Postgres's `REPEATABLE READ` (snapshot isolation) additionally prevents
phantom reads, which is stricter than the SQL standard technically
requires at that level — full anomaly table and concrete two-session
timelines in `transactions-and-locking.sql` Section C.
*Follow-up:* "If you set your application's isolation level to
`SERIALIZABLE`, what MUST change in your application code?" → It must
catch and retry on serialization failure (Postgres raises SQLSTATE
`40001`, "could not serialize access due to read/write dependencies") —
`SERIALIZABLE` trades "never anomalous" for "sometimes forced to abort and
retry," and code written assuming `READ COMMITTED` semantics typically has
no such retry loop, so flipping the isolation level without adding one
introduces new user-facing failures under load.

**Scenario:** "Design the fix for this exact bug: two customers buy the
last unit of the same product at the same instant; both requests read
`quantity_on_hand = 1`, both decide the purchase is valid, both decrement
it, and now stock reads -1 despite the `CHECK` constraint supposedly
preventing that. What's actually going on, and how do you fix it?"
*Ideal answer:* first, clarify that the `CHECK` constraint DOES prevent
the literal `-1` value from ever being written — if this bug is observed,
either the constraint is missing, or (more likely in a real interview
trap) the two decrements were each valid in isolation (`1 - 1 = 0`,
computed twice independently against the same stale read of `1`) so both
succeed individually and the SECOND one overwrites the first's result,
landing on `0` rather than `-1` — a **lost update**, not a constraint
violation, and arguably a worse bug because nothing errors at all. The fix
is `SELECT ... FOR UPDATE` (pessimistic — the second transaction's read
blocks until the first commits, so it sees the true post-decrement value
before deciding) or a `WHERE version = ?` optimistic check (the second
transaction's `UPDATE` matches zero rows and must retry against the
now-current value). Full walkthrough with a sequence diagram in
`transactions-and-locking.sql` Section D and
`diagrams/transaction-locking-flow.md`.
*Follow-up:* "Which of the two fixes would you pick for this exact 'last
unit of a hot product' scenario, and why?" → Pessimistic — this is
precisely the high-contention-on-one-row case where optimistic locking's
retry storm (many transactions all failing and re-reading repeatedly) is
worse than simply queueing behind a lock.

---

## Partitioning

**Beginner:** "What is table partitioning, in one sentence, and name one
concrete benefit?"
*Ideal answer:* splitting one logical table into multiple physical tables
by a key (commonly a date range), transparent to queries — one concrete
benefit: dropping an entire old partition (e.g. a year-old month of
orders) is a near-instant metadata operation, versus a slow `DELETE`
scanning and removing millions of rows individually.

**Intermediate:** "What has to change about a table's primary key to
partition it in PostgreSQL, and why?"
*Ideal answer:* the partition key must be included in every unique
constraint, including the primary key — `orders`' `PRIMARY KEY
(order_id)` becomes `PRIMARY KEY (order_id, created_at)`. This is because
Postgres enforces uniqueness per-partition, not globally across all
partitions at once, so it requires the partition key to be part of the
constraint to make that per-partition enforcement meaningful. See
`partitioning-example.sql` Section A.
*Follow-up:* "What's the knock-on effect on other tables?" → Every foreign
key referencing the partitioned table's primary key must now include the
partition key column too, rippling the schema change outward.

**Senior:** "A 300-million-row `orders` table is causing both slow
dashboard queries and slow monthly data-retention `DELETE` jobs. Would you
recommend partitioning, an index, both, or neither — and how do you decide?"
*Ideal answer:* diagnose the two problems separately, because they have
different fixes. The slow dashboard query might be fixable with a good
composite index alone if it's scoped to a reasonably narrow, well-indexed
range (see `indexing.sql`) — don't reach for partitioning just because the
table is big. The slow retention `DELETE`, however, is a strong,
partitioning-specific signal: no index makes a `DELETE` of millions of
rows fast, because the mechanics (find matching rows, remove them, log
each removal to WAL, leave dead tuples for `VACUUM`) don't change based on
index presence — an index only speeds up *finding* the rows, not the
delete/WAL/vacuum cost of removing them. If the retention requirement is
real and recurring, that alone often justifies partitioning regardless of
whether the dashboard query needed it.
*Follow-up:* "What would you check before committing to partitioning, given
the migration cost?" → Confirm the real query patterns actually align with
the intended partition key (date), estimate the migration approach and
downtime/online-migration cost, and confirm an automation plan exists for
ongoing partition creation (pg_partman or equivalent) so the project
doesn't quietly regress into a manually-maintained, error-prone process a
few months later.

**Scenario:** "Your team partitioned `orders` by month a year ago. A new
engineer proposes also partitioning `customers` by signup month 'for
consistency.' How do you respond?"
*Ideal answer:* push back, with a specific reason: `customers` almost
certainly doesn't have `orders`' access pattern (dominant queries scoped
to recent time ranges, plus a real bulk-retention requirement) — it's
looked up by `customer_id`, grows far more slowly, and gains little from
pruning while still paying the composite-key/foreign-key ripple cost
described above. Partitioning is a decision made per-table based on that
table's actual size and query/retention pattern, not a stylistic
convention to apply uniformly "for consistency" — `README.md` Section 5's
closing one-liner is exactly this point, and reciting it here shows you
understand partitioning as a targeted trade-off, not a default.

---

## PostgreSQL vs Oracle

**Beginner:** "Name two syntax differences between PostgreSQL and Oracle
that would trip up someone porting a query between them."
*Ideal answer:* row limiting (`LIMIT n` in Postgres vs. historically
`ROWNUM <= n` in Oracle, or `FETCH FIRST n ROWS ONLY` on Oracle 12c+/
Postgres both), and string types (`VARCHAR`/`TEXT` in Postgres vs.
`VARCHAR2` in Oracle, where Oracle also treats an empty string as `NULL`
and Postgres does not).

**Intermediate:** "Explain the classic `ROWNUM` + `ORDER BY` bug in
Oracle."
*Ideal answer:* Oracle's `ROWNUM` pseudo-column is assigned to rows
*before* `ORDER BY` is applied in that same query — so `WHERE ROWNUM <= 10
ORDER BY total_amount DESC` does NOT return the top 10 by `total_amount`;
it grabs an arbitrary 10 rows first (in whatever order the engine happened
to produce them), then sorts only those 10. The fix is to wrap the ordered
query in a subquery and apply `ROWNUM` in the OUTER query, or use Oracle
12c+'s standard `FETCH FIRST 10 ROWS ONLY`, which doesn't have this
ordering trap.

**Senior:** "Compare how PostgreSQL and Oracle implement MVCC, and what
different failure mode each produces under a long-running transaction."
*Ideal answer:* Postgres keeps old row versions in the same table (the
heap) and relies on `VACUUM` to reclaim them — under-vacuuming leads to
table bloat (the table grows physically larger than its live data,
degrading scan performance and disk usage) as the visible failure mode.
Oracle keeps old row versions in separate undo/rollback segments,
reconstructed on demand for a consistent read — if a long-running query
needs an old version that's already been overwritten in the undo segment
(because undo retention wasn't long enough for how long the query ran),
Oracle raises `ORA-01555: snapshot too old` and fails the query outright.
Both are MVCC, both give readers a consistent view without blocking
writers, but the specific way each fails under sustained load differs
meaningfully.
*Follow-up:* "Which failure mode is more dangerous in production, in your
opinion, and why?" → There's no single right answer, but a strong response
notes: Postgres's bloat is a *gradual*, monitorable degradation (visible in
`pg_stat_user_tables`, fixable proactively) whereas Oracle's `ORA-01555` is
a *sudden* query failure that can appear to come out of nowhere if undo
retention isn't being actively watched — arguing gradual-and-monitorable is
generally easier to operate around than sudden-and-query-killing, while
acknowledging both are manageable with proper monitoring.

**Scenario:** "Your company is migrating a legacy Oracle order-processing
system (using `VARCHAR2`, `ROWNUM`-based pagination, and PL/SQL packages
for order placement) to PostgreSQL. List the top 3 things you'd flag as
migration risks before writing a single line of DDL."
*Ideal answer:* (1) audit every `ROWNUM`-based pagination query for the
ordering trap above — these need to be rewritten, not mechanically
translated, since a naive `ROWNUM` → `LIMIT` port can silently preserve the
*bug* rather than the intended behavior; (2) audit for any reliance on
Oracle's empty-string-is-`NULL` behavior (`VARCHAR2` columns compared
against `''`) — Postgres treats `''` and `NULL` as genuinely different
values, so logic relying on the Oracle behavior will silently change
behavior; (3) PL/SQL packages/procedures need a full rewrite to PL/pgSQL,
not a mechanical translation — package-level state, autonomous
transactions, and some cursor patterns don't have 1:1 equivalents, and
this is usually the single largest and riskiest line-item in an
Oracle-to-Postgres migration project, deserving its own dedicated audit
and test plan rather than being treated as "just syntax."
