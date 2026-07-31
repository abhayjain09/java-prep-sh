# Module 7 — Exercises

Do these in order — each builds on the previous one's schema/queries. Work
directly against `sql/schema.sql` (spin up a local `postgres:16` container
or an RDS instance if you want to actually run these — this repo's sandbox
has no database engine, so none of these have been executed here). Where an
exercise asks you to write a query, add it to a scratch `.sql` file and
verify by eyeballing the result set against seed data you insert yourself.

## 1. (Beginner) Seed data and a basic join

Write `INSERT` statements creating: 3 customers, 4 products, 2 orders (one
with 2 lines, one with 1 line), and matching `stock` rows. Then write a
query joining `orders` + `customers` + `order_lines` + `products` (like
`sample-queries.sql` A1) that returns one row per order line, sorted by
order date descending, then by line.

**Check yourself:** does your query use `INNER JOIN` or `LEFT JOIN` for
each relationship, and can you justify each choice the way `README.md`
Section 1 and `sample-queries.sql` A1/A2 do? What would change in the
result if you added a customer with zero orders and switched a `JOIN` to
`LEFT JOIN`?

## 2. (Beginner) Fix a `NOT IN` bug

Add a nullable column, `orders.cancelled_reason VARCHAR(200)` (no `NOT
NULL`), to your local copy of the schema. Write a query using `NOT IN` to
find products that have never appeared in an `order_lines` row whose order
was NOT cancelled — deliberately construct it so the subquery's column can
be `NULL` for some rows. Confirm (by reasoning through it, or actually
running it if you have Postgres available) that the query returns zero
rows even when it obviously shouldn't. Rewrite it with `NOT EXISTS` and
confirm it now returns the correct products.

**Check yourself:** write one sentence explaining exactly why `NOT IN`
breaks with a `NULL` in its subquery's result, in terms of SQL's
three-valued logic (`TRUE`/`FALSE`/`UNKNOWN`).

## 3. (Intermediate) Extend the CTE + window function query

Using `sample-queries.sql` Section C2/D1 as a starting point, write a
single query that returns, per customer: lifetime spend, their
`DENSE_RANK()` by spend, and — new — their **average days between
orders** (hint: you'll need `LAG(created_at) OVER (PARTITION BY
customer_id ORDER BY created_at)` inside a CTE, then `AVG()` of the
date-difference in an outer query). Customers with only one order should
show `NULL` for average days between orders, not zero or an error.

**Check yourself:** why does a customer with exactly one order necessarily
produce a `NULL` from `LAG()`, and why is `NULL` the semantically correct
result here rather than `0`?

## 4. (Intermediate) Design and justify an index

Given this new hot-path query — "for a given product SKU, show all orders
containing it in the last 90 days, most recent first" — write the
`CREATE INDEX` statement(s) you'd add to `indexing.sql` to serve it
efficiently, and explain your column order choice using the leftmost-prefix
reasoning from `indexing.sql` Section D. Then write the (illustrative,
hand-reasoned — no live database required) `EXPLAIN` shape you'd expect to
see before your index exists (`Seq Scan` + high `Rows Removed by Filter`)
versus after (`Index Scan`/`Index Only Scan` + matching `Index Cond`).

**Check yourself:** does an existing index from `indexing.sql`
(`idx_order_lines_sku`, or the composite `idx_orders_customer_created_at`)
already partially serve this query? What's missing that a new index would
add?

## 5. (Senior) Implement the stock-decrement fix both ways

Using `transactions-and-locking.sql` Section D as your template, write out
BOTH a pessimistic (`SELECT ... FOR UPDATE`) and an optimistic (`WHERE
version = ?`) transaction that decrements `stock.quantity_on_hand` for a
SKU, decides which one you'd actually deploy for the following two
scenarios, and justify each choice in a comment:
  - (a) A single, extremely popular SKU during a flash sale where thousands
    of customers are trying to buy the last 20 units simultaneously.
  - (b) A general storefront's day-to-day order flow across thousands of
    different SKUs, where any single SKU is rarely contended by more than
    one concurrent order.

**Check yourself:** for scenario (a), what specifically goes wrong if you
choose optimistic locking instead of pessimistic? Walk through the retry
storm concretely (how many transactions retry, how many times, roughly) —
this is exactly the kind of "why," not just "which," an interviewer will
push on.

## 6. (Scenario) Partition retrofit decision + regulatory retention query

Your company's `orders` table has grown to 200 million rows over 6 years
and a new regulation requires deleting all order data older than 7 years,
automatically, every month, going forward — but ALSO requires that you can
still answer "total revenue per month for the last 24 months" quickly for
a compliance dashboard.

Write up (as SQL comments/pseudocode, referencing `partitioning-example.sql`):
1. The partitioning strategy you'd choose and why (range by month, as
   shown, or would you choose something coarser/finer — justify against
   the 7-year retention requirement specifically).
2. The exact primary-key change required and which other tables/foreign
   keys in `schema.sql` would need to change as a result.
3. The one-line monthly maintenance operation that satisfies the
   retention requirement, and why it's dramatically cheaper than a `DELETE`
   statement at this row count.
4. A `sample-queries.sql`-style CTE/window-function query that answers the
   "total revenue per month for the last 24 months" dashboard requirement
   efficiently against your partitioned table.

**Check yourself:** if a stakeholder asked "why not just add an index on
`created_at` instead of partitioning at all?", what's your honest answer —
where would an index alone fall short specifically for the *retention*
requirement (not the dashboard query, which an index might well handle
fine on its own)?
