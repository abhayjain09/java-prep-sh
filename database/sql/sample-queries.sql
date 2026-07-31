-- =============================================================================
-- database/sql/sample-queries.sql
-- Module 7 — Databases: a progression of queries against schema.sql, from
-- basic joins through subqueries, CTEs, and window functions.
--
-- ENVIRONMENT NOTE: not executed against a live engine (no PostgreSQL/Oracle
-- available in this sandbox) — written and reviewed for syntactic and
-- logical correctness against schema.sql only. Any "sample output" shown in
-- comments is hand-constructed/illustrative, clearly labeled as such, and
-- must not be mistaken for a captured result set.
--
-- Read top to bottom — each section assumes you understood the previous one.
-- =============================================================================


-- =============================================================================
-- SECTION A — Basic SELECT / JOIN across all four tables
-- =============================================================================

-- A1. Every order line with its order, customer, and product context.
-- This is the "give me a human-readable order history" query — the single
-- most common shape of query in any e-commerce/order system.
SELECT
    o.order_id,
    o.status,
    o.created_at,
    c.customer_id,
    c.name           AS customer_name,
    p.sku,
    p.name           AS product_name,
    ol.quantity,
    ol.unit_price,
    ol.line_total
FROM orders       o
JOIN customers    c  ON c.customer_id = o.customer_id
JOIN order_lines  ol ON ol.order_id   = o.order_id
JOIN products     p  ON p.sku         = ol.sku
ORDER BY o.created_at DESC, o.order_id, ol.order_line_id;

-- WHY INNER JOIN HERE, NOT LEFT JOIN: every order_line MUST have a valid
-- order and product (enforced by NOT NULL + FK in schema.sql), and every
-- order MUST have a valid customer — so an INNER JOIN never silently drops
-- rows here. INNER JOIN also lets the query planner potentially use either
-- table as the driving side and pick whichever join order/algorithm
-- (nested loop, hash join, merge join) is cheapest; a LEFT JOIN partially
-- constrains those choices because row-preservation on the left side must
-- be respected regardless of matches.


-- A2. Aggregate per order: line count and computed total, cross-checked
-- against the denormalized orders.total_amount column (see schema.sql's
-- comment on why that column is denormalized and can drift).
SELECT
    o.order_id,
    o.total_amount                       AS stored_total,
    COUNT(ol.order_line_id)              AS line_count,
    COALESCE(SUM(ol.line_total), 0)      AS computed_total,
    o.total_amount - COALESCE(SUM(ol.line_total), 0) AS drift
FROM orders      o
LEFT JOIN order_lines ol ON ol.order_id = o.order_id
GROUP BY o.order_id, o.total_amount
HAVING o.total_amount <> COALESCE(SUM(ol.line_total), 0);   -- only show rows where the cache has drifted

-- WHY LEFT JOIN HERE, DELIBERATELY: unlike A1, we WANT to see orders with
-- zero lines (COUNT = 0, SUM = NULL -> COALESCE'd to 0) rather than have
-- them silently disappear, because a driftless empty order is itself a
-- data-quality signal worth surfacing. This pair (A1 vs A2) is a good
-- interview illustration of "choose the join type based on whether losing
-- non-matching rows is correct or a bug for THIS question," not a fixed
-- rule of thumb.


-- =============================================================================
-- SECTION B — Subquery: customers with no orders at all
-- =============================================================================

-- B1. NOT EXISTS — the recommended approach.
SELECT c.customer_id, c.name, c.email
FROM customers c
WHERE NOT EXISTS (
    SELECT 1
    FROM orders o
    WHERE o.customer_id = c.customer_id
);

-- B2. LEFT JOIN / IS NULL — equivalent result, different execution shape.
-- Often just as fast (the planner can rewrite either into a semi/anti join
-- internally), but less immediately readable as "customers lacking a
-- match" to someone skimming the query.
SELECT c.customer_id, c.name, c.email
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.customer_id
WHERE o.order_id IS NULL;

-- B3. NOT IN — INCLUDED AS A DELIBERATE ANTI-PATTERN, NOT A RECOMMENDATION.
-- If ANY row in the subquery's customer_id column were NULL, this returns
-- ZERO rows overall (not "all customers minus those with orders") because
-- `x NOT IN (1, 2, NULL)` evaluates to UNKNOWN, not TRUE, for every x — a
-- classic three-valued-logic (NULL) trap. orders.customer_id is NOT NULL in
-- this schema so it happens to be safe here, but this pattern is a
-- well-known production bug magnet the moment the subquery's column becomes
-- nullable, which is why NOT EXISTS (B1) is the standard recommendation.
SELECT c.customer_id, c.name
FROM customers c
WHERE c.customer_id NOT IN (
    SELECT o.customer_id FROM orders o   -- safe only because customer_id is NOT NULL here
);


-- =============================================================================
-- SECTION C — Common Table Expressions (CTEs)
-- =============================================================================

-- C1. Non-recursive CTE: per-customer order totals, then filtered/used
-- further in the outer query (the "at minimum" requirement from the brief).
-- A CTE (WITH ...) is a named, temporary result set scoped to the query
-- that follows it — think of it as a query-local, inline view. It exists
-- primarily for READABILITY (breaking a complex query into named,
-- sequential steps) and for referencing the same intermediate result
-- multiple times without repeating the subquery text.
WITH customer_totals AS (
    SELECT
        o.customer_id,
        COUNT(*)                    AS order_count,
        SUM(o.total_amount)         AS lifetime_spend,
        AVG(o.total_amount)         AS avg_order_value
    FROM orders o
    WHERE o.status <> 'CANCELLED'   -- cancelled orders shouldn't count toward spend
    GROUP BY o.customer_id
)
SELECT
    c.customer_id,
    c.name,
    ct.order_count,
    ct.lifetime_spend,
    ct.avg_order_value
FROM customer_totals ct
JOIN customers c ON c.customer_id = ct.customer_id
WHERE ct.lifetime_spend > (SELECT AVG(lifetime_spend) FROM customer_totals)  -- above-average spenders
ORDER BY ct.lifetime_spend DESC;

-- PERFORMANCE NOTE (Postgres-specific): since Postgres 12, a CTE referenced
-- exactly once is "inlined" into the outer query by default (folded, just
-- like a subquery would be) unless you force materialization with
-- `WITH customer_totals AS MATERIALIZED (...)`. Before Postgres 12, EVERY
-- CTE was an "optimization fence" — always fully materialized, planner
-- couldn't push predicates from the outer query down into it. This is a
-- real, frequently-asked senior interview gotcha: "are CTEs always slower
-- than subqueries?" — the honest answer is "it depended on your Postgres
-- version; on 12+, usually no, the planner treats a singly-referenced CTE
-- like an inline subquery unless you opt into MATERIALIZED."
-- ORACLE: PL/SQL's WITH clause ("subquery factoring") has supported this
-- kind of inlining/optimization for longer; Oracle also supports the
-- `/*+ MATERIALIZE */` optimizer hint to force materialization explicitly.


-- C2. Two chained CTEs: first compute per-customer totals, then rank them —
-- demonstrates a CTE referencing values that will feed a window function
-- in the next section, and shows CTEs composing (a CTE can be built from
-- another CTE defined earlier in the same WITH clause).
WITH customer_totals AS (
    SELECT o.customer_id, SUM(o.total_amount) AS lifetime_spend
    FROM orders o
    WHERE o.status <> 'CANCELLED'
    GROUP BY o.customer_id
),
ranked_customers AS (
    SELECT
        customer_id,
        lifetime_spend,
        RANK() OVER (ORDER BY lifetime_spend DESC) AS spend_rank
    FROM customer_totals
)
SELECT rc.spend_rank, c.name, rc.lifetime_spend
FROM ranked_customers rc
JOIN customers c ON c.customer_id = rc.customer_id
WHERE rc.spend_rank <= 10
ORDER BY rc.spend_rank;


-- C3. (BONUS / OPTIONAL) Recursive CTE — generates a month calendar between
-- the earliest and latest order, useful for reporting ("show me a row per
-- month even for months with zero orders", which a plain GROUP BY can
-- never produce on its own since it has no data to group for missing
-- months). This also sets up the reasoning behind partitioning-example.sql
-- (partitioning by month only makes sense once you think in monthly
-- buckets like this).
WITH RECURSIVE months AS (
    -- Anchor member: the first month, truncated to the 1st.
    SELECT date_trunc('month', MIN(created_at)) AS month_start
    FROM orders

    UNION ALL

    -- Recursive member: keeps adding one month until we pass the latest
    -- order's month. Each iteration references the PREVIOUS iteration's
    -- row via the CTE's own name (`months`) — this self-reference is what
    -- makes it "recursive" rather than an ordinary CTE, and it is the ONLY
    -- place in standard SQL where a query can reference itself.
    SELECT month_start + INTERVAL '1 month'
    FROM months
    WHERE month_start + INTERVAL '1 month' <= (SELECT date_trunc('month', MAX(created_at)) FROM orders)
)
SELECT
    m.month_start,
    COUNT(o.order_id)                    AS orders_placed,
    COALESCE(SUM(o.total_amount), 0)     AS revenue
FROM months m
LEFT JOIN orders o
       ON date_trunc('month', o.created_at) = m.month_start
GROUP BY m.month_start
ORDER BY m.month_start;

-- SAFETY NOTE ON RECURSIVE CTEs: an unbounded/incorrectly-terminating
-- recursive CTE loops forever (or until it exhausts memory) because there's
-- no built-in max-iteration guard. Always make sure the WHERE clause in the
-- recursive member provably converges toward the termination condition —
-- here, month_start strictly increases by 1 month every iteration and the
-- loop stops once it exceeds the known MAX(created_at), so it is bounded by
-- construction. Postgres also supports `... UNION ALL SELECT ... FROM
-- months WHERE ... LIMIT n` style guards, but a provably-terminating
-- predicate is the more robust habit. Classic recursive CTE use cases
-- outside this example: org charts / manager hierarchies, bill-of-materials
-- explosions, graph traversal (e.g. "all categories under Electronics").


-- =============================================================================
-- SECTION D — Window functions
-- =============================================================================

-- D1. RANK() and DENSE_RANK(): top customers by lifetime spend. The
-- difference between them only shows up when there are ties, so it's worth
-- reading closely:
--   RANK()       -- ties share a rank, but the NEXT rank skips ahead
--                 --   (1, 2, 2, 4, 5 -- rank 3 is "used up" by the tie)
--   DENSE_RANK() -- ties share a rank, and the NEXT rank does NOT skip
--                 --   (1, 2, 2, 3, 4)
-- ROW_NUMBER() (shown for comparison) never ties — it arbitrarily breaks
-- ties by whatever order the tied rows happen to come out in unless you add
-- more ORDER BY columns to fully disambiguate them.
SELECT
    c.customer_id,
    c.name,
    lifetime_spend,
    RANK()       OVER (ORDER BY lifetime_spend DESC) AS spend_rank,
    DENSE_RANK() OVER (ORDER BY lifetime_spend DESC) AS spend_dense_rank,
    ROW_NUMBER() OVER (ORDER BY lifetime_spend DESC) AS spend_row_number
FROM (
    SELECT o.customer_id, SUM(o.total_amount) AS lifetime_spend
    FROM orders o
    WHERE o.status <> 'CANCELLED'
    GROUP BY o.customer_id
) totals
JOIN customers c ON c.customer_id = totals.customer_id
ORDER BY spend_rank
LIMIT 10;


-- D2. Running total of revenue over time via SUM() OVER (ORDER BY ...).
-- The window frame defaults to `RANGE BETWEEN UNBOUNDED PRECEDING AND
-- CURRENT ROW` when ORDER BY is present without an explicit frame clause —
-- i.e. "sum everything from the start up through the current row" — which
-- is exactly a running/cumulative total. Written out explicitly below for
-- clarity (and because relying on the implicit default is a common source
-- of confusion in interviews when RANGE vs ROWS behave differently with
-- tied ORDER BY values).
SELECT
    order_id,
    created_at,
    total_amount,
    SUM(total_amount) OVER (
        ORDER BY created_at
        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    ) AS running_total_revenue
FROM orders
WHERE status <> 'CANCELLED'
ORDER BY created_at;

-- WHY ROWS INSTEAD OF RANGE HERE: RANGE groups "peer" rows that share the
-- same ORDER BY value into the same frame boundary (so two orders with the
-- IDENTICAL created_at timestamp would each see the OTHER already included
-- in their own running total, which double-counts pairs at the same
-- instant). ROWS instead treats each physical row as its own frame step
-- regardless of ties — the correct choice for a running total over
-- individually-countable rows like orders.


-- D3. LAG()/LEAD(): order-to-order comparison per customer — "how does this
-- order compare to the customer's previous one," and "how many days until
-- their next order." This is the SQL-level equivalent of a common
-- reporting/analytics ask ("customer churn risk = days since last order
-- exceeds N").
SELECT
    o.customer_id,
    o.order_id,
    o.created_at,
    o.total_amount,
    LAG(o.total_amount)  OVER w AS previous_order_amount,
    o.total_amount - LAG(o.total_amount) OVER w AS amount_change_vs_previous,
    LAG(o.created_at)    OVER w AS previous_order_at,
    o.created_at - LAG(o.created_at) OVER w AS time_since_previous_order,
    LEAD(o.created_at)   OVER w AS next_order_at
FROM orders o
WHERE o.status <> 'CANCELLED'
WINDOW w AS (PARTITION BY o.customer_id ORDER BY o.created_at)
ORDER BY o.customer_id, o.created_at;

-- NOTE: the `WINDOW w AS (...)` clause names a window definition once and
-- reuses it across multiple OVER (w) references — purely a readability
-- convenience for when (as here) several window functions in the same
-- SELECT share an identical PARTITION BY / ORDER BY. LAG/LEAD default to a
-- 1-row offset and return NULL for a customer's very first (LAG) or most
-- recent (LEAD) order, since there is no previous/next row within that
-- customer's partition — a NULL here means "this is the first (or last)
-- order for this customer," not missing/bad data.
