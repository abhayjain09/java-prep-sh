# database/

**Status:** Not started — coming in Module 7, once entities exist in `spring/`.

Planned coverage:
- PostgreSQL and Oracle: dialect differences relevant to interviews.
- SQL from basic to advanced: joins, subqueries, CTEs, window functions.
- Execution plans and indexing strategy for the Order/Inventory schema (e.g. indexing `orders.customer_id`, `order_lines.order_id`).
- Partitioning, transactions, ACID properties, locking (optimistic vs. pessimistic) for concurrent stock updates.
- Query optimization and common anti-patterns.

See the root [README.md](../README.md) for the full module roadmap and current progress.
