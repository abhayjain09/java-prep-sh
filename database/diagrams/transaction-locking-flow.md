# Transaction & Locking Flow — Stock Decrement Race Condition

Companion diagram to `sql/transactions-and-locking.sql` Section D. Shows two
concurrent sessions both trying to sell the last few units of the same SKU,
first WITHOUT protection (the bug), then WITH `SELECT ... FOR UPDATE`
(pessimistic locking) fixing it.

## 1. The bug — unprotected read-then-write (READ COMMITTED, no locking)

```mermaid
sequenceDiagram
    participant A as Session A (sell 3)
    participant DB as stock table (WIDGET-1, qty=5)
    participant B as Session B (sell 4)

    A->>DB: BEGIN
    A->>DB: SELECT quantity_on_hand (reads 5)
    B->>DB: BEGIN
    B->>DB: SELECT quantity_on_hand (ALSO reads 5)
    Note over A: app checks 5 >= 3 -> OK
    Note over B: app checks 5 >= 4 -> OK
    A->>DB: UPDATE quantity_on_hand = 5 - 3 = 2
    A->>DB: COMMIT
    B->>DB: UPDATE quantity_on_hand = 5 - 4 = 1 (based on STALE read of 5)
    B->>DB: COMMIT
    Note over DB: Final qty = 1, but 3+4=7 units sold from only 5 -- OVERSOLD by 2
```

This is the database-level twin of `Inventory.reserve()`'s documented Java
race condition (`java-basics/src/main/java/com/interviewprep/orders/domain/Inventory.java`)
— same read-check-write gap, different layer (DB transactions instead of
JVM threads).

## 2. The fix — pessimistic locking with `SELECT ... FOR UPDATE`

```mermaid
sequenceDiagram
    participant A as Session A (sell 3)
    participant DB as stock table (WIDGET-1, qty=5)
    participant B as Session B (sell 4)

    A->>DB: BEGIN
    A->>DB: SELECT ... FOR UPDATE (reads 5, LOCKS the row)
    B->>DB: BEGIN
    B->>DB: SELECT ... FOR UPDATE (BLOCKS -- row is locked by A)
    Note over A: app checks 5 >= 3 -> OK
    A->>DB: UPDATE quantity_on_hand = 5 - 3 = 2
    A->>DB: COMMIT (releases lock)
    Note over B: B's blocked SELECT now proceeds, reads the CURRENT value: 2
    Note over B: app checks 2 >= 4 -> FAILS
    B->>DB: ROLLBACK (raise InsufficientStockException-equivalent, no corrupt write)
    Note over DB: Final qty = 2, correctly reflects only A's sale -- no oversell
```

## 3. The alternative fix — optimistic locking with a `version` column

```mermaid
sequenceDiagram
    participant A as Session A (sell 3)
    participant DB as stock table (WIDGET-1, qty=5, version=12)
    participant B as Session B (sell 4)

    A->>DB: SELECT quantity_on_hand, version (reads 5, v12) -- no lock taken
    B->>DB: SELECT quantity_on_hand, version (ALSO reads 5, v12) -- no lock taken
    A->>DB: UPDATE ... SET qty=2, version=13 WHERE sku=... AND version=12
    Note over DB: matches 1 row -- version was still 12 -- succeeds
    A->>DB: COMMIT
    B->>DB: UPDATE ... SET qty=1, version=13 WHERE sku=... AND version=12
    Note over DB: matches 0 rows -- version is now 13, not 12 -- UPDATE affects nothing
    Note over B: app MUST check rows-affected == 0, detect the conflict, and retry from a fresh SELECT
```

## Which one applies to which real scenario

| Scenario | Preferred approach | Why |
|---|---|---|
| Flash sale, one SKU everyone wants right now | Pessimistic (`FOR UPDATE`) | High contention on the same row — cheaper to queue than to retry repeatedly |
| Normal order flow across many different customers/SKUs | Optimistic (`version` column) | Low contention — most transactions touch different rows, so locking overhead isn't worth paying on every write |
| Crash mid-transaction (either approach) | Database transaction (`BEGIN`/`COMMIT`/`ROLLBACK`) | Neither locking strategy matters if the whole statement sequence isn't wrapped in one atomic transaction to begin with — see `transactions-and-locking.sql` Section A for why this is *more* than what `OrderService.placeOrder()`'s hand-rolled try/catch provides in Java (it also survives a crash, not just a caught exception) |
