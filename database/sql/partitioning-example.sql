-- =============================================================================
-- database/sql/partitioning-example.sql
-- Module 7 — Databases: range-partitioning the orders table by month, and
-- a discussion of when partitioning is worth the complexity.
--
-- ENVIRONMENT NOTE: not executed against a live engine. Written and
-- reviewed for correctness only.
--
-- WHY orders SPECIFICALLY, AND WHY BY created_at: orders is the table in
-- this domain most likely to grow unboundedly over time (every placed
-- order is a new row, forever) and is overwhelmingly queried by recent
-- time ranges in practice ("this month's orders," "last quarter's
-- revenue") -- exactly the access pattern range partitioning by date is
-- built for. Contrast with customers/products, which grow much more slowly
-- and are typically looked up by id/sku rather than by a time range --
-- partitioning either of those would add complexity with little benefit.
-- =============================================================================


-- =============================================================================
-- SECTION A — Creating a partitioned variant of orders
-- =============================================================================

-- IMPORTANT CONSTRAINT THAT SHAPES THIS WHOLE FILE: PostgreSQL requires the
-- partition key to be part of EVERY unique constraint on a partitioned
-- table, including the PRIMARY KEY. schema.sql's orders table has
-- `PRIMARY KEY (order_id)` alone, which is NOT sufficient for a partitioned
-- table -- the primary key must become a COMPOSITE (order_id, created_at).
-- This is one of the most common "gotchas" when retrofitting partitioning
-- onto an existing table, and a very fair senior-interview question:
-- "what changes are needed to partition an existing table with a
-- single-column primary key?" This is exactly why partitioning is shown
-- here as a SEPARATE demonstration table (orders_partitioned) rather than
-- rewriting schema.sql's orders table in place -- so both the "before"
-- (schema.sql) and "after" (this file) shapes stay visible for comparison.

DROP TABLE IF EXISTS orders_partitioned CASCADE;

CREATE TABLE orders_partitioned (
    order_id      VARCHAR(40)    NOT NULL,
    customer_id   VARCHAR(64)    NOT NULL REFERENCES customers (customer_id),
    status        VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    total_amount  NUMERIC(14,2)  NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version       INT            NOT NULL DEFAULT 0,

    CONSTRAINT chk_orders_partitioned_status CHECK (
        status IN ('PENDING', 'CONFIRMED', 'SHIPPED', 'DELIVERED', 'CANCELLED')
    ),

    -- Composite primary key: partition key (created_at) MUST be included.
    -- NOTE: this means order_id ALONE is no longer guaranteed globally
    -- unique by this constraint (only the (order_id, created_at) PAIR is)
    -- -- in practice you'd also want a separate, non-partitioned lookup
    -- table or a UNIQUE constraint understanding if pure order_id lookups
    -- without a known created_at need to be fast/guaranteed-unique; a common
    -- real-world compromise is a `UNIQUE (order_id)` GLOBAL index simulated
    -- via a small non-partitioned "order_id -> partition key" lookup table,
    -- since Postgres has no native global unique index across partitions.
    PRIMARY KEY (order_id, created_at)

) PARTITION BY RANGE (created_at);

-- Foreign keys FROM order_lines_partitioned TO a partitioned parent must
-- also reference the composite key -- shown here for completeness, not
-- wired into schema.sql's order_lines to avoid a second parallel schema:
-- CREATE TABLE order_lines_partitioned (
--     order_line_id BIGINT GENERATED ALWAYS AS IDENTITY,
--     order_id      VARCHAR(40) NOT NULL,
--     order_created_at TIMESTAMPTZ NOT NULL,  -- must be carried alongside order_id to reference the composite PK
--     ...
--     FOREIGN KEY (order_id, order_created_at) REFERENCES orders_partitioned (order_id, created_at)
-- );
-- This FK-composite-key propagation is itself a real cost of partitioning a
-- parent table -- every child table's foreign key grows an extra column.


-- =============================================================================
-- SECTION B — Creating the monthly partitions themselves
-- =============================================================================

-- Each partition is a REAL, separate physical table under the covers,
-- automatically routed to by Postgres based on which range the row's
-- created_at falls into. Queries and DML against orders_partitioned "just
-- work" as if it were one table -- partition routing is transparent to the
-- application/ORM layer.

CREATE TABLE orders_2026_01 PARTITION OF orders_partitioned
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

CREATE TABLE orders_2026_02 PARTITION OF orders_partitioned
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');

CREATE TABLE orders_2026_03 PARTITION OF orders_partitioned
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');

-- A DEFAULT partition catches any row whose created_at falls outside every
-- explicitly-defined range above -- without one, an INSERT for a
-- not-yet-provisioned month FAILS outright rather than silently going
-- somewhere wrong. In production this is usually paired with a scheduled
-- job (pg_partman is the standard extension for this) that creates next
-- month's partition automatically, ahead of time, so the DEFAULT partition
-- ideally stays empty and is really just a safety net.
CREATE TABLE orders_default PARTITION OF orders_partitioned DEFAULT;

-- Indexes are created PER-PARTITION automatically when you create an index
-- on the PARENT (orders_partitioned) -- Postgres propagates it to every
-- existing and future partition. Re-run the same indexing strategy as
-- indexing.sql, just against the partitioned parent:
CREATE INDEX idx_orders_partitioned_customer_id ON orders_partitioned (customer_id);


-- =============================================================================
-- SECTION C — Why partitioning helps (and when it's needless complexity)
-- =============================================================================

-- WHEN PARTITIONING HELPS:
--   1. VERY LARGE TABLES (the common rule of thumb is "tens of millions of
--      rows and growing," not a fixed number -- measure, don't guess) where
--      most queries and maintenance operations only touch a RECENT slice of
--      the data. "Partition pruning": if a query's WHERE clause constrains
--      created_at to a range Postgres can prove falls entirely within one
--      or a few partitions, it skips scanning every OTHER partition
--      entirely -- effectively shrinking the table it has to search for
--      that query, similar in spirit to an index but working at the
--      physical-table-file level rather than a row level.
--   2. BULK DATA LIFECYCLE MANAGEMENT: "delete all orders older than 7
--      years" (a common regulatory retention rule at financial companies)
--      becomes `DROP TABLE orders_2019_01;` -- an near-instant metadata
--      operation -- instead of a `DELETE FROM orders WHERE created_at <
--      ...` that must scan and delete millions of rows one at a time,
--      generate a huge amount of WAL, and leave dead tuple bloat behind
--      for autovacuum to clean up later. This is frequently the SINGLE
--      biggest real-world reason companies partition transactional tables.
--   3. VACUUM/maintenance operations scale per-partition rather than
--      against one monolithic table -- a stuck or slow autovacuum on one
--      month's partition doesn't block maintenance on the others.
--   4. Time-series-shaped access patterns generally (metrics, logs, events,
--      audit trails, and yes, orders) where "recent" data is both the
--      hottest for reads/writes and the least likely to ever be UPDATEd
--      again once the month has passed.

-- WHEN PARTITIONING IS NEEDLESS COMPLEXITY (the honest counterpoint --
-- interviewers respect "I wouldn't do this here" as much as knowing how):
--   1. Tables under roughly a few million rows rarely see a measurable
--      benefit -- a good B-tree index on created_at (see indexing.sql)
--      already gives efficient range scans at that scale, and a regular
--      table has none of partitioning's added operational surface area.
--   2. Every partitioned table pays a real complexity tax: the composite
--      primary key change shown in Section A propagates to every foreign
--      key referencing it; cross-partition queries (rare here, but
--      "aggregate over the LAST 6 months" spans multiple partitions) need
--      the planner to combine results from several physical tables, which
--      is still fast but is genuinely more moving parts than one table;
--      and partition MAINTENANCE (creating next month's partition ahead of
--      time, archiving/dropping old ones) is an ongoing operational
--      responsibility that must be automated (pg_partman, a cron job, or
--      equivalent) or you eventually hit the DEFAULT-partition/missing-
--      partition failure mode described in Section B.
--   3. If your actual query patterns DON'T align with the partition key
--      (e.g. you constantly query "all orders for customer X" across their
--      entire history, never scoped by date), partitioning by date buys
--      you little for reads and only helps with the lifecycle-management
--      angle (point 2 above) -- you'd need to know your real workload
--      before choosing created_at (versus, say, customer_id via hash
--      partitioning) as the partition key at all.
--   4. Migrating an EXISTING large table to partitioned is itself a
--      nontrivial, typically online-with-care operation (create the new
--      partitioned table, backfill in batches, dual-write or use logical
--      replication during cutover) -- not a five-minute ALTER TABLE. That
--      migration cost is a real factor in the decision, separate from
--      whether partitioning would help going forward.

-- INTERVIEW-READY ONE-LINER: "Partition when the table is large enough
-- that pruning and lifecycle management (bulk drop of old partitions)
-- measurably help, and your query patterns actually align with the
-- partition key -- not by default on every table 'because it scales
-- better,' since it adds real schema and operational complexity that isn't
-- free."
