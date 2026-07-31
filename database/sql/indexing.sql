-- =============================================================================
-- database/sql/indexing.sql
-- Module 7 — Databases: indexes for the hot-path queries in
-- sample-queries.sql, plus a B-tree vs GIN/GiST discussion and a worked
-- composite-index column-order example.
--
-- ENVIRONMENT NOTE: not executed against a live engine. EXPLAIN/EXPLAIN
-- ANALYZE output shown below is HAND-WRITTEN, ILLUSTRATIVE EXAMPLE OUTPUT
-- meant to show the *shape* of a plan and how to read it — it is NOT a real
-- captured plan from any actual database, and the numbers are invented for
-- teaching purposes only. Do not cite these numbers as benchmark data.
-- =============================================================================


-- =============================================================================
-- SECTION A — Foreign-key indexes (the ones Postgres does NOT create for you)
-- =============================================================================

-- IMPORTANT INTERVIEW POINT: PostgreSQL automatically creates an index to
-- back every PRIMARY KEY and UNIQUE constraint (it needs one to enforce the
-- uniqueness check efficiently) — but it does NOT automatically index a
-- plain FOREIGN KEY column. This trips up people coming from ORMs or other
-- databases that DO auto-index FKs. Without these indexes:
--   - Every "orders for this customer" query (customer_id lookup) does a
--     sequential scan of the whole orders table.
--   - Every "lines for this order" or "orders containing this product"
--     query does a sequential scan of order_lines.
--   - Deleting a row from the PARENT table (e.g. a product) requires
--     Postgres to scan the CHILD table (order_lines) to check the FK
--     constraint isn't violated — without an index on the child's FK
--     column, that check itself becomes a full table scan, making DELETEs
--     on parent tables progressively slower as child tables grow.

CREATE INDEX idx_orders_customer_id       ON orders (customer_id);
CREATE INDEX idx_order_lines_order_id     ON order_lines (order_id);
CREATE INDEX idx_order_lines_sku          ON order_lines (sku);

-- WHY B-TREE (the implicit default — `CREATE INDEX ... ON t (col)` means
-- `USING btree`): B-tree indexes support equality (=) AND range predicates
-- (<, <=, >, >=, BETWEEN), efficient sorted-order scans (great for ORDER BY
-- / MIN / MAX), and are what >95% of indexes in a typical OLTP schema
-- should be. They're the right default for foreign keys, which are almost
-- always queried with equality ("all order_lines WHERE order_id = X").


-- =============================================================================
-- SECTION B — Supporting the other hot-path queries from sample-queries.sql
-- =============================================================================

-- B1. Section A1/A2 join and sort on created_at frequently (D2's running
-- total, D3's LAG/LEAD, partitioning-example.sql's range partitioning) — an
-- index lets Postgres satisfy `ORDER BY created_at` via an index scan
-- instead of a sort step, and lets range predicates (WHERE created_at
-- BETWEEN ...) use an index range scan instead of a sequential scan.
CREATE INDEX idx_orders_created_at ON orders (created_at);

-- B2. Section D — window functions partitioned by customer_id and ordered
-- by created_at (`PARTITION BY o.customer_id ORDER BY o.created_at`) are
-- served much more cheaply by a COMPOSITE index matching that exact
-- (partition columns..., order columns...) shape than by two separate
-- single-column indexes — see the column-order discussion in Section D
-- below for why the order (customer_id, created_at) specifically, not the
-- reverse, is what makes this useful.
CREATE INDEX idx_orders_customer_created_at ON orders (customer_id, created_at);
-- NOTE: idx_orders_customer_id (Section A) is now technically redundant
-- with this composite index for pure equality lookups on customer_id alone
-- — a composite index on (customer_id, created_at) can also serve queries
-- that filter ONLY on customer_id (see Section D's leftmost-prefix
-- explanation). In a real migration you would likely drop the
-- single-column idx_orders_customer_id once this composite index exists,
-- to avoid paying write-amplification cost for two overlapping indexes.
-- Both are kept here side-by-side deliberately, for teaching contrast.


-- =============================================================================
-- SECTION C — B-tree vs GIN vs GiST (brief, "when would you reach for these")
-- =============================================================================

-- GIN (Generalized Inverted Index): built for columns where each row logically
-- contains MULTIPLE searchable "tokens" — full-text search (tsvector),
-- JSONB containment (@>), arrays (ANY/@>). A GIN index maps each token to
-- the list of rows containing it (an inverted index, like a search engine).
--
-- Example use case in THIS domain: full-text search on product names
-- ("find all products whose name matches 'wireless mouse'" with typo
-- tolerance / stemming, not just a LIKE '%wireless%' scan). A plain B-tree
-- cannot do this efficiently — LIKE '%...%' with a leading wildcard cannot
-- use a B-tree index at all (it can only use one for a *prefix* search,
-- LIKE 'wireless%', because B-tree order only helps when you know how the
-- match starts).
--
-- The pg_trgm extension (trigram matching) + GIN is the standard Postgres
-- answer for this:
--   CREATE EXTENSION IF NOT EXISTS pg_trgm;
--   CREATE INDEX idx_products_name_trgm ON products USING gin (name gin_trgm_ops);
-- This lets `WHERE name ILIKE '%wireless%'` and even fuzzy `similarity(name, 'wireles mouse')`
-- queries use the index instead of scanning every row.
-- For true linguistic full-text search (stemming "running" to "run",
-- ranking relevance), Postgres's native tsvector/tsquery + GIN is the
-- fuller-featured option:
--   ALTER TABLE products ADD COLUMN name_search tsvector
--       GENERATED ALWAYS AS (to_tsvector('english', name)) STORED;
--   CREATE INDEX idx_products_name_search ON products USING gin (name_search);
--   -- query: SELECT * FROM products WHERE name_search @@ to_tsquery('english', 'wireless & mouse');
-- ORACLE equivalent: Oracle Text (CONTEXT / CTXCAT indexes) plays the same
-- role as tsvector + GIN.

-- GiST (Generalized Search Tree): a more general-purpose indexing
-- FRAMEWORK for data with notions of "overlap" or "distance" rather than
-- pure equality — geometric types, range types (tsrange, int4range,
-- daterange), and (with the btree_gist extension) EXCLUSION CONSTRAINTS.
--
-- Example use case NOT directly in this domain but a realistic extension of
-- it: if a warehouse/reservation system needed "no two reservations for the
-- same SKU may overlap in time," that's naturally expressed as a GiST-backed
-- EXCLUDE constraint over a tsrange column — something a B-tree fundamentally
-- cannot enforce (B-tree only knows "equal or not / less-or-greater," it has
-- no concept of interval overlap).
--
-- RULE OF THUMB FOR AN INTERVIEW ANSWER: "B-tree for equality/range/sort on
-- scalar values (the overwhelming default); GIN when a single column holds
-- multiple indexable values per row (full text, JSONB, arrays); GiST for
-- geometric/range/nearest-neighbor and exclusion constraints." Naming GIN
-- and GiST correctly and knowing roughly when each applies is usually
-- enough depth for a senior SQL round — implementing a GiST operator class
-- from scratch is not expected outside specialist database roles.


-- =============================================================================
-- SECTION D — Composite index column order: why it matters, worked example
-- =============================================================================

-- Take idx_orders_customer_created_at ON orders (customer_id, created_at)
-- from Section B2. A composite (multi-column) B-tree index is physically
-- sorted first by the FIRST column, then by the second column WITHIN each
-- value of the first — like a phone book sorted by (last_name, first_name):
-- great for "find everyone named Smith" and "find everyone named Smith,
-- first name John," but USELESS for "find everyone with first name John"
-- (across all last names) without scanning the whole book, because the
-- book isn't sorted by first name at all.
--
-- This is the "LEFTMOST PREFIX" rule: a composite index on (A, B) can serve:
--   - WHERE A = ?                    (uses only the A prefix)
--   - WHERE A = ? AND B = ?          (uses the full A, B path)
--   - WHERE A = ? ORDER BY B         (index is already sorted by B within A)
-- but CANNOT efficiently serve (without a full index scan, not a seek):
--   - WHERE B = ?                    (B alone is not a usable prefix)
--   - ORDER BY B alone (across all A) (index isn't globally sorted by B)

-- Query 1 — benefits from (customer_id, created_at) as defined:
-- "this customer's orders, most recent first" — matches the leftmost
-- prefix (equality on customer_id) then uses the index's existing sort
-- order on created_at within that customer, avoiding a separate sort step.
SELECT order_id, created_at, total_amount
FROM orders
WHERE customer_id = 'CUST-001'
ORDER BY created_at DESC;

-- Query 2 — would NOT benefit from (customer_id, created_at), needs the
-- REVERSED column order (created_at, customer_id) instead:
-- "all orders placed in a date range, regardless of customer" — filters
-- only on created_at, which is the SECOND column, so it can't be used as a
-- seek predicate through this particular index; Postgres would fall back
-- to a sequential scan (or use the separate idx_orders_created_at
-- single-column index from Section B1 instead, which is exactly why that
-- index exists alongside the composite one rather than being redundant).
SELECT order_id, customer_id, created_at
FROM orders
WHERE created_at BETWEEN '2026-01-01' AND '2026-01-31';

-- TAKEAWAY: column order in a composite index must match your most
-- important queries' filter/sort pattern, "most selective / most-commonly
-- equality-filtered column first" as a starting heuristic — but always
-- validate against your REAL query patterns and EXPLAIN plans, not just the
-- heuristic in isolation. Postgres 11+ also supports "index-only scans" for
-- covering indexes (INCLUDE clause) — e.g.
--   CREATE INDEX idx_orders_customer_created_at_covering
--       ON orders (customer_id, created_at) INCLUDE (total_amount, status);
-- lets Query 1 above be answered ENTIRELY from the index (customer_id,
-- created_at, total_amount, status are all present in the index itself)
-- without a second lookup ("heap fetch") into the actual table rows at all
-- — a further optimization worth mentioning once the basic leftmost-prefix
-- concept lands.


-- =============================================================================
-- SECTION E — ILLUSTRATIVE EXAMPLE EXPLAIN OUTPUT (NOT REAL, NOT CAPTURED)
-- =============================================================================

-- The comment block below shows the KIND of thing EXPLAIN ANALYZE prints
-- and how to read it, for Query 1 above, with and without
-- idx_orders_customer_created_at. These numbers are invented for teaching
-- purposes — there is no live database in this environment to run this
-- against. If you want real numbers, run
--   EXPLAIN (ANALYZE, BUFFERS) SELECT ...
-- yourself against a populated Postgres instance.

-- EXPLAIN (ANALYZE, BUFFERS) SELECT order_id, created_at, total_amount
-- FROM orders WHERE customer_id = 'CUST-001' ORDER BY created_at DESC;
--
-- --- ILLUSTRATIVE EXAMPLE OUTPUT: BEFORE the composite index exists ---
-- Sort  (cost=1284.39..1286.89 rows=1000 width=48) (actual time=9.812..9.918 rows=42 loops=1)
--   Sort Key: created_at DESC
--   Sort Method: quicksort  Memory: 30kB
--   ->  Seq Scan on orders  (cost=0.00..1234.00 rows=1000 width=48) (actual time=0.024..9.601 rows=42 loops=1)
--         Filter: (customer_id = 'CUST-001'::text)
--         Rows Removed by Filter: 49958
-- Planning Time: 0.145 ms
-- Execution Time: 9.981 ms
--
-- HOW TO READ THIS: "Seq Scan on orders" means Postgres read every row in
-- the table and threw away ("Rows Removed by Filter") everything that
-- didn't match customer_id — expensive as the table grows. The separate
-- "Sort" node above it means the 42 matching rows then had to be sorted by
-- created_at at query time because nothing already returned them in that
-- order.
--
-- --- ILLUSTRATIVE EXAMPLE OUTPUT: AFTER idx_orders_customer_created_at ---
-- Index Scan using idx_orders_customer_created_at on orders
--     (cost=0.29..8.52 rows=42 width=48) (actual time=0.031..0.089 rows=42 loops=1)
--   Index Cond: (customer_id = 'CUST-001'::text)
-- Planning Time: 0.098 ms
-- Execution Time: 0.112 ms
--
-- HOW TO READ THIS: "Index Scan ... Index Cond: (customer_id = ...)" means
-- Postgres seeks directly to the matching rows via the index instead of
-- reading the whole table, AND there is no separate "Sort" node — the index
-- already returns rows in created_at order (descending, matching the query
-- and the index's default ascending order read backwards, which Postgres
-- can do just as cheaply), so the ORDER BY is satisfied for free by the
-- index's physical ordering. The estimated cost dropped from ~1286 to ~8.5,
-- and (in this invented example) execution time from ~10ms to ~0.1ms — a
-- ~100x improvement is a very plausible order of magnitude for this kind of
-- fix on a table with tens of thousands of rows, but the EXACT numbers here
-- are illustrative, not measured.
