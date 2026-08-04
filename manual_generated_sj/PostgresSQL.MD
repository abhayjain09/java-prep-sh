# Oracle / PostgreSQL — Advanced SQL & Performance Tuning Interview Prep

**Scope:** Basic → Advanced SQL, CTEs, Window Functions, Indexing, Execution Plan Tuning, Oracle vs PostgreSQL differences.

Sample schema used throughout (assume it exists in both DBs):

```sql
employees(emp_id, name, dept_id, salary, hire_date, manager_id)
departments(dept_id, dept_name, location)
orders(order_id, customer_id, order_date, amount, status)
customers(customer_id, name, city)
```

---

## PART 1 — SQL FUNDAMENTALS

### Q1. What is the logical order of execution of a SQL query?
**Answer:** SQL is written as `SELECT → FROM → WHERE → GROUP BY → HAVING → ORDER BY`, but it **executes** in this order:

1. `FROM` / `JOIN` — build the working row set
2. `WHERE` — filter rows before grouping
3. `GROUP BY` — group rows
4. `HAVING` — filter groups
5. `SELECT` — compute expressions/aliases
6. `DISTINCT`
7. `ORDER BY`
8. `LIMIT` / `OFFSET` (or `FETCH FIRST` / `ROWNUM` in Oracle)

**Why it matters:** you can't use a `SELECT` alias in `WHERE` (alias doesn't exist yet), but you *can* use it in `ORDER BY` (runs after `SELECT`).

```sql
SELECT dept_id, COUNT(*) AS emp_count
FROM employees
WHERE salary > 30000        -- filters rows first
GROUP BY dept_id
HAVING COUNT(*) > 5         -- filters groups
ORDER BY emp_count DESC;    -- can use alias here
```

### Q2. WHERE vs HAVING?
- `WHERE` filters **rows** before aggregation, cannot use aggregate functions.
- `HAVING` filters **groups** after aggregation, used with `GROUP BY`.

```sql
-- Wrong: WHERE COUNT(*) > 5  ❌ (aggregate not allowed in WHERE)
-- Correct:
SELECT dept_id, COUNT(*) FROM employees
GROUP BY dept_id HAVING COUNT(*) > 5;
```

### Q3. Difference between `DELETE`, `TRUNCATE`, `DROP`?
| Command | Rollback? | Resets identity/auto-increment | Fires triggers | Removes structure |
|---|---|---|---|---|
| DELETE | Yes (DML, logged row-by-row) | No | Yes | No |
| TRUNCATE | Postgres: yes (transactional DDL); Oracle: no (auto-commits) | Yes | No (usually) | No |
| DROP | Oracle: no; Postgres: yes (DDL is transactional) | N/A | N/A | Yes, removes table |

**Key interview point:** PostgreSQL DDL (including `TRUNCATE`, `DROP`, `ALTER`) is **transactional** — can be rolled back inside a transaction block. Oracle DDL implicitly commits, so it **cannot** be rolled back.

### Q4. `UNION` vs `UNION ALL` vs `INTERSECT` vs `EXCEPT`/`MINUS`
- `UNION` — combines results, removes duplicates (implicit `DISTINCT`, so it sorts/hashes — costly).
- `UNION ALL` — combines results, keeps duplicates, **no dedup cost** → always prefer if duplicates are acceptable/impossible.
- `INTERSECT` — common rows in both queries.
- PostgreSQL: `EXCEPT`. Oracle: `MINUS`. Both return rows in the first query not in the second.

```sql
-- Active customers in 2025 but NOT in 2026 (Postgres)
SELECT customer_id FROM orders WHERE order_date >= '2025-01-01' AND order_date < '2026-01-01'
EXCEPT
SELECT customer_id FROM orders WHERE order_date >= '2026-01-01';

-- Same in Oracle uses MINUS instead of EXCEPT
```

### Q5. NULL handling — `NULL = NULL`, `COALESCE`, `NVL`, `NULLIF`
- `NULL = NULL` evaluates to `NULL` (unknown), not `TRUE`. Use `IS NULL` / `IS NOT NULL`.
- `COALESCE(a, b, c)` — ANSI standard, returns first non-null. Works in both DBs.
- `NVL(a, b)` — Oracle-only, 2-argument version of COALESCE.
- `NULLIF(a, b)` — returns `NULL` if `a = b`, else returns `a`. Useful to avoid divide-by-zero:
```sql
SELECT amount / NULLIF(quantity, 0) FROM orders;  -- avoids div/0 error
```

### Q6. `IN` vs `EXISTS` vs `ANY`/`ALL`
- `IN` — materializes the subquery list; good for small, non-null static lists. **Careful:** `NOT IN` with a NULL in the subquery returns zero rows (a classic gotcha).
- `EXISTS` — stops at first match (short-circuits), works well with correlated subqueries and large datasets, null-safe.
- `ANY`/`SOME` — true if comparison holds for at least one row; `ALL` — true for all rows.

```sql
-- Gotcha: if dept_id has any NULL, this returns NOTHING, even for valid ids
SELECT * FROM employees WHERE dept_id NOT IN (SELECT dept_id FROM departments); 

-- Safer version
SELECT e.* FROM employees e
WHERE NOT EXISTS (SELECT 1 FROM departments d WHERE d.dept_id = e.dept_id);
```

---

## PART 2 — JOINS

### Q7. Explain all join types with examples
```sql
-- INNER JOIN: only matching rows
SELECT e.name, d.dept_name FROM employees e
INNER JOIN departments d ON e.dept_id = d.dept_id;

-- LEFT JOIN: all left rows + matched right (NULL if no match)
SELECT e.name, d.dept_name FROM employees e
LEFT JOIN departments d ON e.dept_id = d.dept_id;

-- RIGHT JOIN: all right rows + matched left
SELECT e.name, d.dept_name FROM employees e
RIGHT JOIN departments d ON e.dept_id = d.dept_id;

-- FULL OUTER JOIN: all rows from both, unmatched side is NULL
SELECT e.name, d.dept_name FROM employees e
FULL OUTER JOIN departments d ON e.dept_id = d.dept_id;

-- CROSS JOIN: Cartesian product (every row x every row)
SELECT e.name, d.dept_name FROM employees e CROSS JOIN departments d;

-- SELF JOIN: table joined to itself (e.g., employee-manager)
SELECT e.name AS employee, m.name AS manager
FROM employees e
LEFT JOIN employees m ON e.manager_id = m.emp_id;
```

### Q8. How do you write an ANTI JOIN and a SEMI JOIN (no native keyword in SQL)?
- **Semi join** — return rows from A that have a match in B, **without duplicating** A's rows even if B has multiple matches. Implemented via `EXISTS` or `IN`.
- **Anti join** — return rows from A that have **no match** in B. Implemented via `NOT EXISTS` or `LEFT JOIN ... WHERE B.key IS NULL`.

```sql
-- Semi join: customers who placed at least one order
SELECT c.* FROM customers c
WHERE EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.customer_id);

-- Anti join: customers who never ordered
SELECT c.* FROM customers c
WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.customer_id);

-- Anti join alternative (watch out for NULLs, prefer NOT EXISTS)
SELECT c.* FROM customers c
LEFT JOIN orders o ON o.customer_id = c.customer_id
WHERE o.customer_id IS NULL;
```
**Interview tip:** `NOT EXISTS` is generally preferred over `LEFT JOIN ... IS NULL` for anti-joins because it's null-safe and the optimizer usually converts it to an efficient anti-join plan directly.

### Q9. Why can joins cause duplicate rows, and how do you debug it?
If the join key isn't unique on one side, each match creates a new row (fan-out). E.g., joining `orders` to `order_items` (1-to-many) then aggregating `orders.amount` will overcount if you `SUM` before deduplicating. Fix: aggregate the many-side first in a subquery/CTE, *then* join.

```sql
-- Wrong: amount gets multiplied by number of order_items rows
SELECT o.order_id, o.amount, COUNT(oi.item_id)
FROM orders o JOIN order_items oi ON oi.order_id = o.order_id
GROUP BY o.order_id, o.amount;

-- Correct: pre-aggregate the many side
SELECT o.order_id, o.amount, item_counts.cnt
FROM orders o
JOIN (SELECT order_id, COUNT(*) cnt FROM order_items GROUP BY order_id) item_counts
  ON item_counts.order_id = o.order_id;
```

---

## PART 3 — SUBQUERIES & CTEs

### Q10. Scalar vs Correlated vs Nested subqueries
- **Scalar subquery** — returns a single value, usable anywhere an expression is expected.
- **Correlated subquery** — references a column from the outer query; conceptually re-executed per outer row (optimizer often rewrites as a join).
- **Nested subquery** — independent of the outer query, executes once.

```sql
-- Scalar
SELECT name, salary, (SELECT AVG(salary) FROM employees) AS company_avg
FROM employees;

-- Correlated: employees earning above their own department's average
SELECT e.name, e.salary FROM employees e
WHERE e.salary > (SELECT AVG(e2.salary) FROM employees e2 WHERE e2.dept_id = e.dept_id);
```

### Q11. What is a CTE? Why use it over a subquery?
A **Common Table Expression** (`WITH` clause) is a named, temporary result set scoped to one statement.

**Benefits:** readability, reusability within the same query, enables recursion, can improve modularity of complex query logic.

**Important nuance (interview favorite):**
- **PostgreSQL (≥12):** CTEs are **inlined/optimized** by default like subqueries (not automatically materialized), unless you add `MATERIALIZED` explicitly, or the CTE is referenced multiple times/has side effects.
- **PostgreSQL (<12):** CTEs were always an **optimization fence** (materialized), which sometimes hurt performance.
- **Oracle:** the optimizer decides whether to inline or materialize (`WITH` clause) using cost-based decisions; you can hint `MATERIALIZE`/`INLINE`.

```sql
WITH dept_avg AS (
    SELECT dept_id, AVG(salary) AS avg_sal
    FROM employees
    GROUP BY dept_id
)
SELECT e.name, e.salary, d.avg_sal
FROM employees e
JOIN dept_avg d ON d.dept_id = e.dept_id
WHERE e.salary > d.avg_sal;

-- Force materialization in Postgres 12+
WITH dept_avg AS MATERIALIZED (
    SELECT dept_id, AVG(salary) AS avg_sal FROM employees GROUP BY dept_id
)
SELECT * FROM dept_avg;
```

### Q12. Recursive CTE — write one (classic interview question: org chart / employee hierarchy)
```sql
WITH RECURSIVE org_chart AS (
    -- anchor member: top-level managers (no manager)
    SELECT emp_id, name, manager_id, 1 AS level
    FROM employees
    WHERE manager_id IS NULL

    UNION ALL

    -- recursive member: join back to CTE
    SELECT e.emp_id, e.name, e.manager_id, oc.level + 1
    FROM employees e
    JOIN org_chart oc ON e.manager_id = oc.emp_id
)
SELECT * FROM org_chart ORDER BY level, name;
```
**Oracle equivalent** uses `CONNECT BY PRIOR` (Oracle's hierarchical query, predates recursive CTEs — also supported):
```sql
SELECT emp_id, name, manager_id, LEVEL
FROM employees
START WITH manager_id IS NULL
CONNECT BY PRIOR emp_id = manager_id
ORDER SIBLINGS BY name;
```
**Gotcha to mention:** always guard recursive CTEs against cycles (`CONNECT BY` needs `NOCYCLE` in Oracle for cyclic graphs; in Postgres you'd track a "visited" array and break out).

---

## PART 4 — WINDOW FUNCTIONS (very common in interviews)

### Q13. What's the difference between a window function and `GROUP BY`?
`GROUP BY` collapses rows into one row per group. Window functions (`OVER (...)`) compute an aggregate/ranking **per row** while **keeping all rows** — the "window" of related rows is defined by `PARTITION BY` (like a mini `GROUP BY`) and optionally ordered/framed.

```sql
SELECT name, dept_id, salary,
       AVG(salary) OVER (PARTITION BY dept_id) AS dept_avg,
       salary - AVG(salary) OVER (PARTITION BY dept_id) AS diff_from_avg
FROM employees;
```

### Q14. `ROW_NUMBER()` vs `RANK()` vs `DENSE_RANK()`
```sql
SELECT name, dept_id, salary,
       ROW_NUMBER() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS rn,
       RANK()       OVER (PARTITION BY dept_id ORDER BY salary DESC) AS rnk,
       DENSE_RANK() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS drnk
FROM employees;
```
- `ROW_NUMBER()` — unique sequential number, no ties (1,2,3,4...).
- `RANK()` — same rank for ties, **skips** next rank (1,2,2,4...).
- `DENSE_RANK()` — same rank for ties, **no gaps** (1,2,2,3...).

**Classic use case — "Nth highest salary per department" / "top-N per group":**
```sql
SELECT * FROM (
    SELECT name, dept_id, salary,
           ROW_NUMBER() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS rn
    FROM employees
) t
WHERE rn <= 3;   -- top 3 earners per department
```

**Classic use case — Remove duplicate rows keeping only one:**
```sql
DELETE FROM employees WHERE emp_id IN (
    SELECT emp_id FROM (
        SELECT emp_id,
               ROW_NUMBER() OVER (PARTITION BY name, dept_id ORDER BY emp_id) AS rn
        FROM employees
    ) t WHERE rn > 1
);
```

### Q15. `LAG()` / `LEAD()` — accessing prior/next row without a self-join
```sql
-- Month-over-month change in order amount per customer
SELECT customer_id, order_date, amount,
       LAG(amount) OVER (PARTITION BY customer_id ORDER BY order_date) AS prev_amount,
       amount - LAG(amount) OVER (PARTITION BY customer_id ORDER BY order_date) AS change
FROM orders;

-- LEAD with default value and offset
SELECT order_id, order_date,
       LEAD(order_date, 1, DATE '2099-12-31') OVER (ORDER BY order_date) AS next_order_date
FROM orders;
```

### Q16. `FIRST_VALUE`, `LAST_VALUE`, `NTH_VALUE`, and the frame clause gotcha
```sql
SELECT name, dept_id, salary,
       FIRST_VALUE(name) OVER (PARTITION BY dept_id ORDER BY salary DESC) AS top_earner
FROM employees;
```
**Gotcha (frequently tested):** `LAST_VALUE` with the *default* frame (`RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW`) only sees up to the current row, so it often just returns the current row's own value instead of the true last value in the partition. Fix by specifying the full frame:
```sql
SELECT name, dept_id, salary,
       LAST_VALUE(name) OVER (
           PARTITION BY dept_id ORDER BY salary DESC
           ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
       ) AS lowest_earner
FROM employees;
```

### Q17. Running totals & moving averages (frame clause: `ROWS` vs `RANGE`)
```sql
-- Running total
SELECT order_date, amount,
       SUM(amount) OVER (ORDER BY order_date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running_total
FROM orders;

-- 3-row moving average
SELECT order_date, amount,
       AVG(amount) OVER (ORDER BY order_date ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) AS moving_avg_3
FROM orders;
```
`ROWS` = physical row offset. `RANGE` = logical value offset (ties with the same `ORDER BY` value are treated as one peer group) — important distinction when duplicate order-by values exist.

### Q18. `NTILE()` — bucketing rows into N groups
```sql
-- Split employees into 4 salary quartiles
SELECT name, salary, NTILE(4) OVER (ORDER BY salary) AS quartile
FROM employees;
```

### Q19. Percent/cumulative distribution: `PERCENT_RANK()`, `CUME_DIST()`
```sql
SELECT name, salary,
       PERCENT_RANK() OVER (ORDER BY salary) AS pct_rank,
       CUME_DIST()    OVER (ORDER BY salary) AS cume_dist
FROM employees;
```

---

## PART 5 — INDEXING

### Q20. How does a B-Tree index work, and when does it help?
A B-Tree index stores sorted key values in a balanced tree, giving `O(log n)` lookup for equality and range predicates (`=`, `<`, `>`, `BETWEEN`, `LIKE 'prefix%'`, `ORDER BY`, `IS NULL`). It does **not** help `LIKE '%suffix'` (leading wildcard) since the index is sorted left-to-right.

```sql
CREATE INDEX idx_emp_dept ON employees(dept_id);           -- PostgreSQL/Oracle syntax same
```

### Q21. Composite (multi-column) indexes and the "leftmost prefix" rule
An index on `(dept_id, salary)` can be used for:
- `WHERE dept_id = 10` ✅
- `WHERE dept_id = 10 AND salary > 5000` ✅ (uses both columns)
- `WHERE salary > 5000` ❌ (can't skip the leading column — full scan or different index needed)

**Column order matters:** put the most selective / most frequently equality-filtered column first, unless query patterns dictate otherwise.

```sql
CREATE INDEX idx_emp_dept_sal ON employees(dept_id, salary);
```

### Q22. Covering index / Index-Only Scan
A **covering index** includes every column needed by the query, so the engine never touches the table (heap) at all.
```sql
-- Postgres: INCLUDE adds non-key columns for covering without bloating the sort key
CREATE INDEX idx_emp_covering ON employees(dept_id) INCLUDE (name, salary);

SELECT name, salary FROM employees WHERE dept_id = 10;  -- Index Only Scan possible
```
Oracle doesn't have `INCLUDE`, but you can achieve similar results by adding extra columns directly to a composite index, or using an **Index-Organized Table (IOT)**.

**Postgres nuance:** Index-Only Scans additionally require the **visibility map** to be up to date (`VACUUM`), otherwise Postgres still has to check the heap for tuple visibility (MVCC).

### Q23. Partial indexes (Postgres) vs function-based indexes (both)
```sql
-- Partial index: only index "active" orders — smaller, faster, cheaper to maintain
CREATE INDEX idx_active_orders ON orders(customer_id) WHERE status = 'ACTIVE';

-- Function-based index: needed when queries filter on an expression
CREATE INDEX idx_emp_upper_name ON employees (UPPER(name));
SELECT * FROM employees WHERE UPPER(name) = 'JOHN';   -- can now use the index
-- Without the functional index, wrapping a column in a function disables normal index usage.
```
Oracle also supports function-based indexes identically. Oracle doesn't support Postgres-style partial indexes directly, but Oracle 12c+ has similar behavior indirectly via virtual columns + indexing on them.

### Q24. Bitmap indexes — when and why (Oracle-specific, also used internally by Postgres bitmap scans)
Bitmap indexes are efficient for **low-cardinality** columns (few distinct values, e.g., `gender`, `status`) especially in read-heavy/OLAP/data-warehouse workloads with complex `AND`/`OR` filters, because bitmaps can be combined bit-wise very fast. **Not** suited for OLTP with frequent single-row updates (bitmap entries lock large row ranges, causing contention).
```sql
CREATE BITMAP INDEX idx_orders_status ON orders(status);   -- Oracle only, needs Enterprise Edition
```
PostgreSQL doesn't have a persistent bitmap index type, but the planner **dynamically builds an in-memory bitmap** (Bitmap Heap Scan / Bitmap Index Scan) at query time when combining multiple B-tree indexes.

### Q25. Hash indexes vs B-Tree
Hash indexes support only equality (`=`), O(1) average lookup, no range scans, no sorting benefit. PostgreSQL's hash indexes became WAL-logged/crash-safe from v10 onward, but B-Tree remains the default recommendation unless proven otherwise, since B-Tree also supports equality efficiently and is more versatile.

### Q26. When does an index NOT get used? (common troubleshooting question)
- Leading wildcard: `LIKE '%abc'`
- Applying a function on the indexed column without a matching function-based index: `WHERE UPPER(name) = ...` on a plain index on `name`
- Implicit type conversion / mismatched data types (e.g., comparing a `VARCHAR` indexed column to a `NUMBER` literal forces conversion)
- Low selectivity: if the predicate returns a large % of the table, the optimizer prefers a full/sequential scan (index scans have per-row overhead)
- `OR` conditions spanning different columns without matching composite/bitmap strategy (though modern optimizers now often handle this via `BitmapOr`/`UNION` transformation)
- Stale statistics leading the optimizer to misjudge cardinality
- Small tables — full scan is cheaper than the overhead of an index lookup

### Q27. How do indexes affect writes (INSERT/UPDATE/DELETE)?
Every index must be maintained on every write, so more indexes = slower writes, more storage, more WAL/redo generation. This is the classic **read/write tradeoff** — over-indexing a write-heavy OLTP table is a common real-world performance mistake worth mentioning in interviews.

---

## PART 6 — EXECUTION PLANS & QUERY TUNING

### Q28. How do you view an execution plan?
```sql
-- PostgreSQL
EXPLAIN SELECT * FROM employees WHERE dept_id = 10;
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) SELECT * FROM employees WHERE dept_id = 10;

-- Oracle
EXPLAIN PLAN FOR SELECT * FROM employees WHERE dept_id = 10;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);
-- or, for a real execution with actual runtime stats:
SELECT /*+ GATHER_PLAN_STATISTICS */ * FROM employees WHERE dept_id = 10;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'ALLSTATS LAST'));
```
**Key distinction:** `EXPLAIN` alone shows the **estimated** plan (no execution). `EXPLAIN ANALYZE` (Postgres) / `DBMS_XPLAN.DISPLAY_CURSOR` (Oracle) actually **runs** the query and shows real row counts/timings — critical for spotting cases where the optimizer's estimate is way off from reality (a huge red flag for tuning).

### Q29. How do you read a Postgres `EXPLAIN ANALYZE` plan?
```
Hash Join  (cost=1.09..25.90 rows=100 width=64) (actual time=0.05..0.45 rows=98 loops=1)
  Hash Cond: (e.dept_id = d.dept_id)
  ->  Seq Scan on employees e  (cost=0.00..20.00 rows=1000 width=40) (actual time=0.01..0.10 rows=1000 loops=1)
  ->  Hash  (cost=1.05..1.05 rows=4 width=24) (actual time=0.02..0.02 rows=4 loops=1)
        ->  Seq Scan on departments d  (cost=0.00..1.05 rows=4 width=24)
```
- **Read bottom-up, inside-out** — innermost/deepest nodes execute first.
- `cost=startup..total` — arbitrary optimizer cost units (not ms), used only for comparing plans.
- `rows` (estimated) vs `actual rows` — a **big divergence** signals stale statistics or a bad row estimate; the fix is often `ANALYZE`/`VACUUM ANALYZE`, or improved statistics (`ALTER TABLE ... SET STATISTICS`), or restructuring the predicate.
- `loops` — for nested-loop inner sides, the actual time/rows shown is **per loop**; multiply by loops for the true total.
- Look for `Seq Scan` on large tables where an `Index Scan` was expected — a common tuning finding.

### Q30. Nested Loop vs Hash Join vs Merge Join — when does the optimizer pick each?
| Join type | Best when | Notes |
|---|---|---|
| **Nested Loop** | Small outer set + indexed inner lookup | For each outer row, probe inner via index. Great when outer is small/highly filtered. Bad without an index on the inner join key (turns into O(n·m)). |
| **Hash Join** | Large unsorted sets, equality join only | Build an in-memory hash table on smaller input, probe with larger input. Needs enough `work_mem`/`hash_area_size`, else spills to disk. |
| **Merge Join** | Both inputs already sorted (or cheap to sort) on join key | Efficient for pre-sorted/indexed inputs; good for large sorted joins, common in DW/reporting. |

**Interview one-liner:** "If join columns are indexed and one side is small → nested loop. If both are large and unsorted → hash join. If both are already sorted (e.g., via index) → merge join."

### Q31. What are optimizer statistics and why do they matter?
The cost-based optimizer (CBO) uses table/column statistics (row counts, distinct value counts, histograms, null fraction) to estimate selectivity and choose a plan. Stale statistics after bulk loads/deletes → bad cardinality estimates → bad plan choice (e.g., nested loop instead of hash join on a now-huge table).
```sql
-- PostgreSQL
ANALYZE employees;
VACUUM ANALYZE employees;   -- also reclaims dead tuples + updates visibility map
ALTER TABLE employees ALTER COLUMN dept_id SET STATISTICS 500;  -- more detailed histogram

-- Oracle
EXEC DBMS_STATS.GATHER_TABLE_STATS('SCHEMA_NAME','EMPLOYEES');
EXEC DBMS_STATS.GATHER_SCHEMA_STATS('SCHEMA_NAME');
```

### Q32. What is a "SARGable" predicate, and why does it matter?
SARGable ("Search ARGument-able") = a predicate written so the optimizer **can** use an index. Wrapping the indexed column in a function or performing arithmetic on it makes it non-SARGable.
```sql
-- Non-SARGable: function on indexed column blocks normal index usage
WHERE TRUNC(hire_date) = DATE '2024-01-01'

-- SARGable rewrite: keep the column bare, move the transformation to the constant
WHERE hire_date >= DATE '2024-01-01' AND hire_date < DATE '2024-01-02'
```

### Q33. What causes a full table scan even with an index present, and is that always bad?
Not always bad — for small tables, or when a query touches a large fraction of rows (low selectivity, e.g., >10-15% of rows), a sequential/full scan can genuinely be cheaper than thousands of random index-row lookups (especially on spinning disks; less true on SSD but still holds due to I/O batching). The optimizer chooses based on cost, not dogma — a "full scan" flag in a plan isn't automatically a bug; investigate selectivity before "fixing" it.

### Q34. How do you tune a slow query — describe your general methodology.
1. **Reproduce & measure** — get `EXPLAIN ANALYZE`, note actual runtime and the slowest node(s).
2. **Check estimates vs actuals** — big mismatches point to stale stats or bad predicates.
3. **Check indexes** — are join/filter/order-by columns indexed? Composite index column order correct? Are indexes even being used?
4. **Check for non-SARGable predicates** — functions/implicit casts on indexed columns.
5. **Check join order & join type** — is a huge table driving a nested loop it shouldn't?
6. **Check for unnecessary work** — `SELECT *` instead of needed columns, unnecessary `DISTINCT`/`ORDER BY`, N+1 query patterns from the app layer.
7. **Check memory settings** — `work_mem` (Postgres) / `PGA/SORT_AREA_SIZE` (Oracle) too small → disk spill for sorts/hashes.
8. **Check for locking/contention** — is the query actually CPU/IO-bound, or waiting on locks?
9. **Consider structural fixes** — partitioning, materialized views, denormalization, summary tables, better data types.
10. **Re-test and compare plans** before/after each change — one variable at a time.

### Q35. What are hints, and should you use them?
Hints (`/*+ INDEX(...) */`, `/*+ USE_HASH */` in Oracle; `pg_hint_plan` extension in Postgres, since vanilla Postgres has no native hint syntax) force the optimizer's hand. **Best practice:** treat as a last resort after confirming via `EXPLAIN` that the optimizer's default choice is genuinely wrong (usually due to stats) — hints are brittle and can become harmful as data grows/changes. Fixing statistics or rewriting the query is usually more durable than hardcoding a hint.

### Q36. What is parameter sniffing / plan caching issues, and how do bind variables interact with it?
When a query plan is cached/reused for a prepared statement, the plan was optimized for the *first* parameter value(s) seen. If later calls use very different, skewed values, the cached plan can be poor for them. Oracle's "bind peeking" and Postgres's "generic vs custom plan" (for prepared statements after ~5 executions, Postgres may switch to a generic plan) both have this trade-off. Mitigations: histograms on skewed columns, `OPTIMIZER_ADAPTIVE_PLANS` (Oracle), or forcing custom re-planning per call in Postgres when skew is severe.

---

## PART 7 — PERFORMANCE TUNING SCENARIOS (be ready to talk through these)

### Q37. A report query joining 5 tables takes 30 seconds. Walk through your approach.
- Get the plan; identify the most expensive node (usually the largest scan or a bad join).
- Check if each join predicate is indexed on both sides.
- Check for a Cartesian product accidentally introduced (missing join condition).
- Consider a **materialized view** if this report runs repeatedly on largely static data:
```sql
-- PostgreSQL
CREATE MATERIALIZED VIEW mv_dept_summary AS
SELECT dept_id, COUNT(*) emp_count, AVG(salary) avg_sal FROM employees GROUP BY dept_id;
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_dept_summary;  -- needs a unique index for CONCURRENTLY

-- Oracle
CREATE MATERIALIZED VIEW mv_dept_summary
BUILD IMMEDIATE REFRESH FAST ON COMMIT
AS SELECT dept_id, COUNT(*) emp_count, AVG(salary) avg_sal FROM employees GROUP BY dept_id;
```
- Consider pre-aggregating in a summary/rollup table refreshed on a schedule.
- Consider partitioning the largest fact table (see Q39).

### Q38. `OFFSET`/`LIMIT` pagination is slow on page 10,000 — why, and how do you fix it?
`OFFSET N` still has to scan and discard the first N rows every time — cost grows linearly with page depth. Fix with **keyset/seek pagination**: remember the last row's sort key and filter past it.
```sql
-- Slow: OFFSET 100000 LIMIT 20
SELECT * FROM orders ORDER BY order_id LIMIT 20 OFFSET 100000;

-- Fast: keyset pagination using the last seen order_id
SELECT * FROM orders WHERE order_id > :last_seen_id ORDER BY order_id LIMIT 20;
```

### Q39. What is table partitioning and when do you use it?
Splitting one logical table into multiple physical segments by a key (range, list, hash), so queries that filter on the partition key only scan relevant partitions ("**partition pruning**"), and maintenance (archiving, bulk delete) becomes fast (drop a partition vs. `DELETE` millions of rows).
```sql
-- PostgreSQL (declarative range partitioning)
CREATE TABLE orders (
    order_id BIGINT, order_date DATE, amount NUMERIC
) PARTITION BY RANGE (order_date);

CREATE TABLE orders_2025 PARTITION OF orders
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE orders_2026 PARTITION OF orders
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');

-- Oracle
CREATE TABLE orders (order_id NUMBER, order_date DATE, amount NUMBER)
PARTITION BY RANGE (order_date) (
    PARTITION p2025 VALUES LESS THAN (DATE '2026-01-01'),
    PARTITION p2026 VALUES LESS THAN (DATE '2027-01-01')
);
```
Use for very large fact tables, time-series data, archival/purge needs, or to enable partition-wise joins.

### Q40. Deadlocks — what causes them and how do you avoid them?
Two transactions each hold a lock the other needs, in opposite acquisition order (A locks row 1 then wants row 2; B locks row 2 then wants row 1). Both DBs detect and abort one transaction automatically. Prevention: always acquire locks/update rows in a **consistent order** across the application, keep transactions short, use appropriate isolation levels, and consider `SELECT ... FOR UPDATE SKIP LOCKED`/`NOWAIT` for queue-style workloads to avoid blocking altogether.

---

## PART 8 — TRANSACTIONS, ISOLATION, CONCURRENCY

### Q41. Explain the four isolation levels and what anomalies they prevent.
| Level | Dirty Read | Non-repeatable Read | Phantom Read |
|---|---|---|---|
| Read Uncommitted | Possible | Possible | Possible |
| Read Committed (Postgres/Oracle default) | Prevented | Possible | Possible |
| Repeatable Read | Prevented | Prevented | Possible (Postgres actually prevents phantoms too, via snapshot) |
| Serializable | Prevented | Prevented | Prevented |

**Postgres nuance:** Postgres implements isolation via **MVCC** (snapshots), not blocking reads. `Read Committed` takes a fresh snapshot per statement; `Repeatable Read` takes one snapshot for the whole transaction; `Serializable` adds predicate-lock-based conflict detection (SSI — Serializable Snapshot Isolation) and can abort transactions with a serialization failure that the app must retry.

**Oracle nuance:** Oracle doesn't offer a true "Read Uncommitted" (dirty reads aren't possible due to its multiversion consistency model); default is `Read Committed`, and it offers `Serializable` (Oracle's flavor is closer to snapshot isolation than textbook serializable, similar caveats apply).

### Q42. What is MVCC, and why does Postgres need `VACUUM`?
Multi-Version Concurrency Control: instead of locking rows for reads, each transaction sees a consistent **snapshot**. `UPDATE`/`DELETE` don't overwrite in place — Postgres writes a new row version and marks the old one dead. `VACUUM` reclaims that dead tuple space and updates the visibility map/statistics; without regular vacuuming, tables **bloat** and index-only scans degrade. Oracle achieves MVCC differently, via **undo segments** (old versions reconstructed from undo/rollback data), so it doesn't need an equivalent vacuum process, but has its own undo-retention tuning considerations (`ORA-01555 snapshot too old`).

### Q43. `SELECT ... FOR UPDATE` — what does it do?
Locks the selected rows for the duration of the transaction so other transactions can't modify/lock them, used for safe read-modify-write patterns (e.g., decrementing inventory).
```sql
BEGIN;
SELECT quantity FROM inventory WHERE product_id = 5 FOR UPDATE;
UPDATE inventory SET quantity = quantity - 1 WHERE product_id = 5;
COMMIT;
```
`FOR UPDATE SKIP LOCKED` is the standard pattern for building a concurrent job queue: each worker grabs unlocked rows only, avoiding contention.

---

## PART 9 — PL/SQL vs PL/pgSQL (procedural extensions)

### Q44. Write a simple stored procedure/function in both dialects.
```sql
-- PL/pgSQL (PostgreSQL)
CREATE OR REPLACE FUNCTION give_raise(p_emp_id INT, p_pct NUMERIC)
RETURNS VOID AS $$
BEGIN
    UPDATE employees SET salary = salary * (1 + p_pct/100) WHERE emp_id = p_emp_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Employee % not found', p_emp_id;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- PL/SQL (Oracle)
CREATE OR REPLACE PROCEDURE give_raise(p_emp_id IN NUMBER, p_pct IN NUMBER) AS
BEGIN
    UPDATE employees SET salary = salary * (1 + p_pct/100) WHERE emp_id = p_emp_id;
    IF SQL%ROWCOUNT = 0 THEN
        RAISE_APPLICATION_ERROR(-20001, 'Employee not found: ' || p_emp_id);
    END IF;
END;
/
```

### Q45. Triggers — a basic audit trigger example.
```sql
-- PostgreSQL
CREATE OR REPLACE FUNCTION audit_salary_change() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.salary <> OLD.salary THEN
        INSERT INTO salary_audit(emp_id, old_salary, new_salary, changed_at)
        VALUES (OLD.emp_id, OLD.salary, NEW.salary, now());
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_salary_audit
AFTER UPDATE ON employees
FOR EACH ROW EXECUTE FUNCTION audit_salary_change();

-- Oracle
CREATE OR REPLACE TRIGGER trg_salary_audit
AFTER UPDATE ON employees
FOR EACH ROW
WHEN (NEW.salary <> OLD.salary)
BEGIN
    INSERT INTO salary_audit(emp_id, old_salary, new_salary, changed_at)
    VALUES (:OLD.emp_id, :OLD.salary, :NEW.salary, SYSTIMESTAMP);
END;
/
```

---

## PART 10 — ORACLE vs POSTGRESQL — QUICK-REFERENCE DIFFERENCES (frequently asked directly)

| Feature | Oracle | PostgreSQL |
|---|---|---|
| Row limiting | `ROWNUM`, or `FETCH FIRST n ROWS ONLY` (12c+) | `LIMIT n OFFSET m` |
| Sequences | `sequence.NEXTVAL` | `nextval('sequence')` or `GENERATED ALWAYS AS IDENTITY` |
| String concat | `\|\|`, `CONCAT()` | `\|\|`, `CONCAT()` |
| Empty string | `''` treated as `NULL` | `''` is a distinct value, not NULL |
| Boolean type | No native BOOLEAN (use NUMBER(1)/CHAR) | Native `BOOLEAN` type |
| Auto-increment | Sequence + trigger, or `IDENTITY` (12c+) | `SERIAL`/`IDENTITY` |
| Merge/Upsert | `MERGE INTO ... USING ...` | `INSERT ... ON CONFLICT DO UPDATE` |
| Hierarchical query | `CONNECT BY PRIOR` | Recursive CTE (`WITH RECURSIVE`) |
| DDL transactional? | No (auto-commits) | Yes |
| JSON support | `JSON` type (21c+), `JSON_VALUE`/`JSON_TABLE` | Rich native `JSONB` with GIN indexing, `->`, `->>`, `@>` operators |
| Undo/versioning | Undo segments | MVCC + VACUUM |
| Procedural language | PL/SQL | PL/pgSQL (also supports PL/Python, PL/Perl, etc.) |
| Full-text search | Oracle Text | Native `tsvector`/`tsquery` + GIN index |
| Partitioning | Enterprise Edition feature (extra license) | Built-in, free |
| Optimizer hints | Rich native hint syntax | No native hints (community `pg_hint_plan` extension) |

### Q46. Write an UPSERT (merge) in both dialects.
```sql
-- PostgreSQL
INSERT INTO inventory (product_id, quantity)
VALUES (5, 10)
ON CONFLICT (product_id)
DO UPDATE SET quantity = inventory.quantity + EXCLUDED.quantity;

-- Oracle
MERGE INTO inventory tgt
USING (SELECT 5 AS product_id, 10 AS quantity FROM dual) src
ON (tgt.product_id = src.product_id)
WHEN MATCHED THEN UPDATE SET tgt.quantity = tgt.quantity + src.quantity
WHEN NOT MATCHED THEN INSERT (product_id, quantity) VALUES (src.product_id, src.quantity);
```

### Q47. Querying JSON (a rising interview topic for Postgres roles)
```sql
-- PostgreSQL — JSONB column `attrs`
SELECT attrs->>'color' AS color FROM products WHERE attrs @> '{"in_stock": true}';
CREATE INDEX idx_attrs_gin ON products USING GIN (attrs);

-- Oracle — JSON column
SELECT JSON_VALUE(attrs, '$.color') AS color FROM products
WHERE JSON_EXISTS(attrs, '$?(@.in_stock == true)');
```

---

## PART 11 — RAPID-FIRE CONCEPTUAL Q&A

**Q48. What's the difference between a clustered and non-clustered index?**
A clustered index determines the physical storage order of table rows (one per table) — Oracle's Index-Organized Table (IOT) is the closest analog; Postgres has no true clustered index but `CLUSTER` can physically reorder a table once (a one-time snapshot, not maintained automatically). A non-clustered/secondary index is a separate structure pointing to row locations, and a table can have many.

**Q49. What is a correlated subquery's performance risk?**
If not rewritten as a join/semi-join internally by the optimizer, it can execute once per outer row (O(n×m)). Modern optimizers (both Oracle CBO and Postgres planner) usually "unnest" simple correlated subqueries into joins automatically, but complex ones may not — worth checking the plan.

**Q50. `COUNT(*)` vs `COUNT(1)` vs `COUNT(column)`?**
`COUNT(*)` counts all rows including nulls (optimizer treats identically to `COUNT(1)` in both DBs — no performance difference in modern versions). `COUNT(column)` counts only non-null values of that column — semantically different, not just a performance choice.

**Q51. What is query plan "spilling to disk," and how do you fix it?**
When a sort or hash operation exceeds allotted memory (`work_mem` in Postgres, `PGA_AGGREGATE_TARGET`/`SORT_AREA_SIZE` in Oracle), it spills intermediate data to temp disk space, which is drastically slower. Fix: increase memory setting (session-level `SET work_mem = '256MB'` for one heavy query is often safer than a global bump), reduce data volume via better filtering, or add an index that avoids the sort altogether (e.g., an index matching `ORDER BY`).

**Q52. How would you find the second highest salary without window functions (classic legacy interview question)?**
```sql
-- Using LIMIT/OFFSET
SELECT DISTINCT salary FROM employees ORDER BY salary DESC LIMIT 1 OFFSET 1;

-- Using a correlated subquery (portable)
SELECT MAX(salary) FROM employees
WHERE salary < (SELECT MAX(salary) FROM employees);

-- Oracle-specific with ROWNUM
SELECT salary FROM (
    SELECT salary FROM employees ORDER BY salary DESC
) WHERE ROWNUM = 2;
```
(Then mention: "In practice today I'd just use `DENSE_RANK()` — it's cleaner and handles ties correctly.")
```sql
SELECT salary FROM (
    SELECT salary, DENSE_RANK() OVER (ORDER BY salary DESC) AS rnk FROM employees
) WHERE rnk = 2;
```

**Q53. What's the difference between `VARCHAR2`/`VARCHAR` and `CHAR`?**
`CHAR(n)` is fixed-length, blank-padded to n; `VARCHAR2`/`VARCHAR(n)` is variable-length, stores only actual data (+ small overhead). Always prefer `VARCHAR`/`VARCHAR2` unless you specifically need fixed-width, padded storage — `CHAR` wastes space and complicates comparisons (trailing space handling differs subtly between Oracle and Postgres).

**Q54. What is a materialized view, and how does it differ from a regular view?**
A regular view is just a stored query (no data of its own — recomputed every time). A materialized view **physically stores** the query result, must be refreshed (manually, on a schedule, `ON COMMIT`, or `CONCURRENTLY` in Postgres to avoid locking readers) — trades staleness for speed, ideal for expensive aggregations on relatively static data.

**Q55. Explain database normalization vs denormalization tradeoffs.**
Normalization (3NF+) minimizes redundancy/update anomalies, ideal for OLTP with heavy writes. Denormalization (star schema, pre-joined/aggregated tables) trades storage/write complexity for much faster reads, typical in OLAP/reporting/data-warehouse design. Real systems often mix both — normalized OLTP core + denormalized reporting layer (materialized views, summary tables, or a separate warehouse).

---

## PART 12 — PRACTICE PROBLEMS (write the query yourself, answers included)

**P1.** Find employees who earn more than their manager.
```sql
SELECT e.name AS employee, e.salary, m.name AS manager, m.salary AS mgr_salary
FROM employees e
JOIN employees m ON e.manager_id = m.emp_id
WHERE e.salary > m.salary;
```

**P2.** Find the department with the highest average salary.
```sql
SELECT dept_id, AVG(salary) AS avg_sal
FROM employees
GROUP BY dept_id
ORDER BY avg_sal DESC
LIMIT 1;   -- Oracle: FETCH FIRST 1 ROWS ONLY
```

**P3.** For each customer, find their most recent order.
```sql
SELECT customer_id, order_id, order_date
FROM (
    SELECT customer_id, order_id, order_date,
           ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY order_date DESC) AS rn
    FROM orders
) t
WHERE rn = 1;
```

**P4.** Find consecutive days where a customer placed orders (gaps-and-islands problem).
```sql
-- "islands" technique: order_date minus a running row_number gives a constant for consecutive dates
WITH numbered AS (
    SELECT customer_id, order_date,
           order_date - (ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY order_date))::int AS grp
    FROM (SELECT DISTINCT customer_id, order_date FROM orders) d
)
SELECT customer_id, MIN(order_date) AS streak_start, MAX(order_date) AS streak_end, COUNT(*) AS streak_len
FROM numbered
GROUP BY customer_id, grp
HAVING COUNT(*) >= 3
ORDER BY customer_id, streak_start;
```

**P5.** Pivot: total order amount per customer per year, one column per year.
```sql
-- PostgreSQL (manual conditional aggregation — portable across both DBs)
SELECT customer_id,
       SUM(CASE WHEN EXTRACT(YEAR FROM order_date) = 2025 THEN amount ELSE 0 END) AS y2025,
       SUM(CASE WHEN EXTRACT(YEAR FROM order_date) = 2026 THEN amount ELSE 0 END) AS y2026
FROM orders
GROUP BY customer_id;

-- Oracle has native PIVOT syntax as an alternative
SELECT * FROM (
    SELECT customer_id, EXTRACT(YEAR FROM order_date) yr, amount FROM orders
)
PIVOT (SUM(amount) FOR yr IN (2025 AS y2025, 2026 AS y2026));
```

**P6.** Find duplicate rows in a table (by all columns or a subset).
```sql
SELECT name, dept_id, COUNT(*)
FROM employees
GROUP BY name, dept_id
HAVING COUNT(*) > 1;
```

**P7.** Running rank of top products by monthly revenue, resetting each month.
```sql
SELECT product_id, month, revenue,
       RANK() OVER (PARTITION BY month ORDER BY revenue DESC) AS rank_in_month
FROM monthly_product_sales;
```

---

## FINAL INTERVIEW TIPS

1. **Always narrate your thought process** while writing SQL live — interviewers weight reasoning as much as the final query.
2. When asked "how would you optimize this," **always mention checking the execution plan first** before proposing a fix — shows you don't guess.
3. Be ready to explain **why** you'd pick `EXISTS` over `IN`, or a `CTE` over a subquery — not just syntax, but tradeoffs.
4. If the interview is Oracle-heavy, brush up on `CONNECT BY`, `MERGE`, `ROWNUM` vs `FETCH FIRST`, `DBMS_STATS`, `DBMS_XPLAN`.
5. If Postgres-heavy, be fluent in `EXPLAIN (ANALYZE, BUFFERS)`, `VACUUM`/bloat, `JSONB`, partial/GIN indexes, `ON CONFLICT`.
6. Mention real numbers if you have them from experience (e.g., "cut a report from 40s to 2s by adding a composite index and switching OFFSET pagination to keyset") — concrete stories land better than pure theory.
