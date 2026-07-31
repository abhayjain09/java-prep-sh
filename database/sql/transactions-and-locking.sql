-- =============================================================================
-- database/sql/transactions-and-locking.sql
-- Module 7 — Databases: transactions, ACID, isolation levels, and locking
-- strategies for the stock-decrement race condition.
--
-- ENVIRONMENT NOTE: not executed against a live engine. Written and
-- reviewed for correctness only. Where two concurrent sessions are shown,
-- the interleaving is depicted with "-- SESSION A" / "-- SESSION B"
-- comments and a numbered timeline — this is a paper trace, not a captured
-- transcript from a real psql session.
--
-- DIRECT TIE-BACK TO JAVA-BASICS: OrderService.placeOrder() in
-- java-basics/src/main/java/com/interviewprep/orders/service/OrderService.java
-- hand-rolls an "all or nothing" guarantee using a try/catch and a Deque of
-- already-reserved lines that get manually released if a later line fails:
--
--     try {
--         for (OrderLine line : requestedLines) {
--             inventory.reserve(line.product().sku(), line.quantity());
--             reserved.push(line);
--         }
--     } catch (InsufficientStockException e) {
--         for (OrderLine line : reserved) {
--             inventory.release(line.product().sku(), line.quantity());
--         }
--         throw e;
--     }
--
-- That Java code's own comment says it plainly: "exactly the 'all or
-- nothing' guarantee a database transaction gives you for free, done by
-- hand here to show the problem a transaction solves." Section A below is
-- the literal database-transaction replacement for that exact block —
-- read them side by side.
-- =============================================================================


-- =============================================================================
-- SECTION A — Wrapping order placement + stock decrement in a real transaction
-- =============================================================================

-- A1. THE HAPPY PATH: place an order for 2 units of SKU 'WIDGET-1', 1 unit
-- of SKU 'GADGET-2'. Everything succeeds, so COMMIT makes it durable.
BEGIN;

    -- Insert the order header first (status defaults to 'PENDING' per schema.sql).
    INSERT INTO orders (order_id, customer_id, status, total_amount, created_at)
    VALUES ('ORD-1001', 'CUST-001', 'PENDING', 0, now());

    -- Insert each line, snapshotting the CURRENT product price into
    -- unit_price (see schema.sql's order_lines.unit_price comment for why).
    INSERT INTO order_lines (order_id, sku, quantity, unit_price)
    SELECT 'ORD-1001', p.sku, 2, p.price FROM products p WHERE p.sku = 'WIDGET-1';

    INSERT INTO order_lines (order_id, sku, quantity, unit_price)
    SELECT 'ORD-1001', p.sku, 1, p.price FROM products p WHERE p.sku = 'GADGET-2';

    -- Decrement stock for each line. The CHECK (quantity_on_hand >= 0)
    -- constraint from schema.sql is the database's OWN enforcement of the
    -- "never oversell" invariant — if either UPDATE below would drive stock
    -- negative, Postgres raises an error and the constraint blocks the
    -- write, no application-level check required (though you still want
    -- one for a fast, friendly error message rather than a raw constraint
    -- violation surfacing to a user).
    UPDATE stock SET quantity_on_hand = quantity_on_hand - 2, version = version + 1, updated_at = now()
    WHERE sku = 'WIDGET-1';

    UPDATE stock SET quantity_on_hand = quantity_on_hand - 1, version = version + 1, updated_at = now()
    WHERE sku = 'GADGET-2';

    -- Sync the denormalized total (see schema.sql's orders.total_amount comment).
    UPDATE orders
    SET total_amount = (SELECT COALESCE(SUM(line_total), 0) FROM order_lines WHERE order_id = 'ORD-1001')
    WHERE order_id = 'ORD-1001';

COMMIT;

-- WHAT THIS BUYS YOU BEYOND OrderService.placeOrder()'s TRY/CATCH:
-- The Java version only protects against an EXCEPTION being thrown mid-loop
-- (InsufficientStockException) — it does NOT protect against the JVM
-- process crashing, the machine losing power, or the network dropping
-- between the second reserve() and the order being fully constructed.
-- A real BEGIN/COMMIT database transaction is ATOMIC across a crash too:
-- if the database process itself dies after the two UPDATEs but before
-- COMMIT, Postgres's write-ahead log (WAL) ensures that on recovery, the
-- ENTIRE transaction is rolled back as if it never started — there is no
-- window where "some order_lines exist but stock wasn't decremented" can
-- survive a crash. That crash-safety guarantee (the "D" in ACID —
-- Durability, plus Atomicity) is something no amount of try/catch in
-- application code can provide on its own; it requires the database
-- engine's transaction log.


-- A2. THE ROLLBACK PATH: same shape, but this time stock is insufficient —
-- the UPDATE would violate the CHECK constraint, so the whole transaction
-- is rolled back automatically, leaving stock and orders exactly as they
-- were (this is the direct SQL analog of OrderService.placeOrder()'s catch
-- block releasing already-reserved lines — except here you don't have to
-- write ANY compensating "undo" code at all; ROLLBACK undoes every
-- statement since BEGIN in one shot).
BEGIN;

    INSERT INTO orders (order_id, customer_id, status, total_amount, created_at)
    VALUES ('ORD-1002', 'CUST-002', 'PENDING', 0, now());

    INSERT INTO order_lines (order_id, sku, quantity, unit_price)
    SELECT 'ORD-1002', p.sku, 999999, p.price FROM products p WHERE p.sku = 'WIDGET-1';

    -- This UPDATE will fail with:
    --   ERROR: new row for relation "stock" violates check constraint "chk_stock_qty_nonneg"
    -- because 999999 units almost certainly exceeds quantity_on_hand.
    UPDATE stock SET quantity_on_hand = quantity_on_hand - 999999
    WHERE sku = 'WIDGET-1';

    -- Once any statement in a transaction errors, Postgres marks the whole
    -- transaction "aborted" — every subsequent statement (even a harmless
    -- SELECT 1) is rejected with "current transaction is aborted, commands
    -- ignored until end of transaction block" until you explicitly issue:
ROLLBACK;

-- After ROLLBACK: neither the ORD-1002 order, its order_line, nor the
-- stock decrement exist — as if the whole block never ran. Compare this to
-- the Java code's catch block, which has to explicitly know how to UNDO a
-- reserve() call (calling release()); the database needed no equivalent
-- "undo" logic written anywhere — ROLLBACK is generic and automatic for
-- ANY set of statements, which is exactly the guarantee application-level
-- try/catch cannot generalize to arbitrary side effects (you'd need to
-- write a compensating action for every single statement type by hand).


-- =============================================================================
-- SECTION B — ACID, restated concretely against this schema
-- =============================================================================
-- Atomicity   — A1/A2 above: either ALL statements between BEGIN/COMMIT take
--               effect, or NONE do (enforced by ROLLBACK, or automatically on
--               crash/error).
-- Consistency — the CHECK/FK/NOT NULL constraints in schema.sql are never
--               violated at any transaction boundary — a transaction that
--               would leave the database in a state violating
--               chk_stock_qty_nonneg is refused, full stop.
-- Isolation   — concurrent transactions don't see each other's uncommitted
--               work (to a degree controlled by the ISOLATION LEVEL — see
--               Section C); this is what makes Section D's race condition
--               analysis meaningful at all.
-- Durability  — once COMMIT returns successfully, the change survives a
--               crash immediately after (guaranteed by the WAL being
--               fsynced before COMMIT acknowledges success).


-- =============================================================================
-- SECTION C — Isolation levels and the anomalies each does/doesn't allow
-- =============================================================================

-- Postgres implements exactly THREE distinct isolation levels internally
-- (READ UNCOMMITTED is accepted as valid SQL syntax but silently treated as
-- READ COMMITTED — Postgres's MVCC design makes a dirty read structurally
-- impossible, so there was never a way to actually implement READ
-- UNCOMMITTED's looser behavior even if you wanted to). This is itself a
-- great interview fact: "does Postgres support READ UNCOMMITTED?" ->
-- "You can SET it, but it behaves identically to READ COMMITTED — Postgres
-- never allows dirty reads at all, at any isolation level."

-- +-------------------+------------+---------------------+---------------+
-- | Isolation Level    | Dirty Read | Non-Repeatable Read | Phantom Read |
-- +-------------------+------------+---------------------+---------------+
-- | READ UNCOMMITTED   | Possible*  | Possible             | Possible     |
-- | READ COMMITTED     | Prevented  | Possible             | Possible     |
-- | REPEATABLE READ    | Prevented  | Prevented            | Prevented**  |
-- | SERIALIZABLE       | Prevented  | Prevented            | Prevented    |
-- +-------------------+------------+---------------------+---------------+
-- *  In Postgres specifically, READ UNCOMMITTED never actually allows a
--    dirty read in practice (see note above) — the SQL standard permits it
--    at this level, but Postgres's implementation doesn't.
-- ** Postgres's REPEATABLE READ (an implementation of "snapshot isolation")
--    prevents phantom reads too, which is STRICTER than the SQL standard
--    technically requires at this level — the standard only mandates
--    phantom prevention at SERIALIZABLE. Oracle's terminology differs
--    further still: Oracle doesn't offer a standard REPEATABLE READ at all
--    — it offers READ COMMITTED (default) and SERIALIZABLE (which in Oracle
--    is actually implemented as snapshot isolation, not true
--    conflict-serializable execution) — see README.md's Postgres-vs-Oracle
--    section for the fuller comparison.

-- DEFINITIONS, WITH A CONCRETE EXAMPLE AGAINST `stock`:
--
-- DIRTY READ: Session B reads a value Session A has written but NOT YET
-- committed; if A then rolls back, B acted on data that never really
-- existed.
--   SESSION A: BEGIN; UPDATE stock SET quantity_on_hand = 0 WHERE sku = 'WIDGET-1'; -- not committed yet
--   SESSION B (READ UNCOMMITTED, standard SQL): SELECT quantity_on_hand FROM stock WHERE sku='WIDGET-1'; -- sees 0
--   SESSION A: ROLLBACK; -- the real value was never 0
--   -- Postgres never actually lets B see the uncommitted 0, at any isolation level.
--
-- NON-REPEATABLE READ: Session B reads the same row twice in one
-- transaction and gets DIFFERENT values because Session A committed a
-- change to that row in between.
--   SESSION B (READ COMMITTED): BEGIN;
--   SESSION B: SELECT quantity_on_hand FROM stock WHERE sku='WIDGET-1'; -- e.g. 50
--   SESSION A: BEGIN; UPDATE stock SET quantity_on_hand = 40 WHERE sku='WIDGET-1'; COMMIT;
--   SESSION B: SELECT quantity_on_hand FROM stock WHERE sku='WIDGET-1'; -- now 40 -- same transaction, different answer!
--   SESSION B: COMMIT;
--   -- REPEATABLE READ or stricter would have Session B see 50 both times
--   -- (a consistent snapshot taken at the start of its transaction).
--
-- PHANTOM READ: Session B re-runs the SAME aggregate/range query twice in
-- one transaction and gets a DIFFERENT SET OF ROWS (not just a changed
-- value on an existing row) because Session A committed an INSERT/DELETE
-- matching that range in between.
--   SESSION B (READ COMMITTED): BEGIN;
--   SESSION B: SELECT COUNT(*) FROM orders WHERE status = 'PENDING'; -- e.g. 5
--   SESSION A: BEGIN; INSERT INTO orders (...) VALUES (..., 'PENDING', ...); COMMIT;
--   SESSION B: SELECT COUNT(*) FROM orders WHERE status = 'PENDING'; -- now 6 -- a new "phantom" row appeared
--   SESSION B: COMMIT;

-- Setting the isolation level for a transaction in Postgres:
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;
    -- ... statements ...
COMMIT;

-- SERIALIZABLE in Postgres can fail an otherwise-valid transaction with:
--   ERROR: could not serialize access due to read/write dependencies among transactions
-- This is EXPECTED, not a bug — it means Postgres detected that committing
-- BOTH concurrent transactions would produce a result impossible to get
-- from running them one-at-a-time in ANY order, so it forces one to abort
-- and retry. Application code using SERIALIZABLE MUST be written to catch
-- this specific error/SQLSTATE ('40001') and retry the whole transaction —
-- this is a common senior-interview follow-up ("what do you have to change
-- in your app code to safely use SERIALIZABLE?").


-- =============================================================================
-- SECTION D — The stock-decrement race condition: pessimistic vs optimistic
-- locking
-- =============================================================================
-- This is the DATABASE-LEVEL equivalent of the EXACT SAME bug documented in
-- domain/Inventory.java's Javadoc ("two threads calling reserve()
-- concurrently on the same SKU can both read the same stock level before
-- either writes back, over-selling the same unit twice") and addressed at
-- the APPLICATION/JVM level in java-advanced/concurrency (synchronization,
-- ConcurrentHashMap.compute(), AtomicInteger per SKU). Here, the "two
-- threads" become "two database sessions/transactions" — same shape of
-- bug, different layer, same underlying cause: a read-then-check-then-write
-- sequence that isn't atomic against a concurrent writer.

-- D1. THE BUG, UNGUARDED (plain SELECT then UPDATE under READ COMMITTED,
-- Postgres's default): shown as a two-session timeline.
--
--   SESSION A                              SESSION B
--   --------------------------------       --------------------------------
--   BEGIN;
--   SELECT quantity_on_hand FROM stock
--     WHERE sku = 'WIDGET-1';   -- reads 5
--                                           BEGIN;
--                                           SELECT quantity_on_hand FROM stock
--                                             WHERE sku = 'WIDGET-1';   -- ALSO reads 5
--   -- app code checks: 5 >= 3 (requested)? yes, proceed
--                                           -- app code checks: 5 >= 4 (requested)? yes, proceed
--   UPDATE stock SET quantity_on_hand = 5 - 3   -- writes 2
--   COMMIT;
--                                           UPDATE stock SET quantity_on_hand = 5 - 4   -- writes 1 (based on the STALE read of 5, not A's committed 2!)
--                                           COMMIT;
--   -- Final quantity_on_hand = 1, but 3 + 4 = 7 units were "sold" out of
--   -- only 5 available -- oversold by 2 units. Both transactions individually
--   -- looked correct; the bug is entirely in the gap between SELECT and
--   -- UPDATE, exactly like Inventory.reserve()'s Java-level race.


-- D2. FIX 1 — PESSIMISTIC LOCKING: SELECT ... FOR UPDATE.
-- FOR UPDATE takes a row-level exclusive lock on the selected row for the
-- rest of the transaction. Any OTHER transaction's SELECT ... FOR UPDATE
-- (or UPDATE/DELETE) against the SAME row BLOCKS — it simply waits — until
-- the first transaction commits or rolls back. This turns the
-- read-then-write sequence back into an effectively atomic unit from the
-- point of view of concurrent stock decrements.
BEGIN;

    -- Session A: this SELECT locks the WIDGET-1 row. If Session B runs the
    -- same statement concurrently, Session B BLOCKS here until Session A's
    -- transaction ends (COMMIT or ROLLBACK) -- it does not proceed to read
    -- a stale value the way D1's plain SELECT did.
    SELECT quantity_on_hand FROM stock WHERE sku = 'WIDGET-1' FOR UPDATE;

    -- Application-level check happens here, against a value now guaranteed
    -- not to change underneath us until we commit.
    -- (pseudocode: if quantity_on_hand < requested_quantity then ROLLBACK and raise InsufficientStockException)

    UPDATE stock SET quantity_on_hand = quantity_on_hand - 3, version = version + 1
    WHERE sku = 'WIDGET-1';

COMMIT;  -- releases the row lock; Session B's blocked SELECT ... FOR UPDATE now proceeds, reading the UPDATED value

-- TRADE-OFFS OF FOR UPDATE (pessimistic):
--   + Simple to reason about: guarantees correctness by construction, no
--     retry logic needed in application code.
--   + Good fit when contention on the SAME row is EXPECTED to be common
--     (a very hot-selling SKU) -- you'd rather serialize access cleanly
--     than burn CPU on repeated failed optimistic attempts.
--   - Holds a lock for the ENTIRE remaining duration of the transaction --
--     if the transaction does slow, unrelated work (an external API call,
--     a slow report query) before committing, every OTHER transaction
--     wanting that same row queues up behind it, hurting throughput.
--   - Can deadlock if two transactions lock multiple rows in different
--     orders (classic "lock ordering" bug -- see the deadlock note below).
--   - FOR UPDATE NOWAIT / FOR UPDATE SKIP LOCKED variants exist for cases
--     where blocking indefinitely is unacceptable: NOWAIT fails immediately
--     with an error instead of waiting; SKIP LOCKED silently skips
--     already-locked rows -- useful for job-queue-style "grab the next
--     available row" patterns, not appropriate for stock decrement (you
--     don't want to silently skip the SKU you actually need).


-- D3. FIX 2 — OPTIMISTIC LOCKING: WHERE version = ? on the UPDATE.
-- Instead of locking anything up front, read normally, then make the
-- UPDATE conditional on nothing else having changed the row since you read
-- it -- using the `version` column from schema.sql. If zero rows are
-- affected, someone else won the race; the application must detect that
-- and retry (or surface a conflict to the user).
BEGIN;

    -- Ordinary read, no lock taken.
    SELECT quantity_on_hand, version FROM stock WHERE sku = 'WIDGET-1';
    -- Suppose this returns quantity_on_hand = 5, version = 12.

    -- The UPDATE's WHERE clause pins BOTH the sku AND the exact version we
    -- read. If another transaction committed a change to this row between
    -- our SELECT and this UPDATE, `version` in the table is no longer 12
    -- (it would be 13+), so this UPDATE matches ZERO rows -- it does NOT
    -- error, it just silently affects 0 rows, which the application MUST
    -- explicitly check for (via the driver's "rows affected" / row count).
    UPDATE stock
    SET quantity_on_hand = 5 - 3,   -- computed in application code from the value just read
        version = version + 1,
        updated_at = now()
    WHERE sku = 'WIDGET-1'
      AND version = 12;

    -- Pseudocode the application must implement:
    --   if (rowsAffected == 0) {
    --       ROLLBACK; retry the whole read-check-update cycle from the top
    --       (re-SELECT the current quantity_on_hand/version and try again),
    --       or surface "someone else already updated this, please retry"
    --       to the caller after a bounded number of attempts.
    --   } else {
    --       COMMIT;
    --   }

COMMIT;

-- TRADE-OFFS OF version-COLUMN (optimistic):
--   + No lock held while the application "thinks" -- much better
--     throughput under LOW-to-MODERATE contention, since transactions
--     never block each other; they just occasionally have to retry.
--   + This is exactly what JPA/Hibernate's @Version annotation automates
--     (Module 5/Spring territory) -- this raw SQL is what that annotation
--     compiles down to under the hood.
--   - Under HIGH contention (many transactions hammering the SAME hot row
--     simultaneously), most attempts fail and retry repeatedly, which can
--     actually be WORSE than pessimistic locking's simple queueing -- this
--     is the mirror image of FOR UPDATE's trade-off.
--   - Requires the application to implement retry logic correctly; forgetting
--     to check "rows affected == 0" silently drops the failed update with no
--     error at all, which is a genuinely dangerous bug to ship.
--   - Needs a version/updated-at column added to every table that wants
--     this protection -- a schema-level cost pessimistic locking doesn't have.

-- WHEN TO PICK WHICH (a good interview framing): pessimistic (FOR UPDATE)
-- for high-contention hot rows where correctness-by-blocking beats
-- burning CPU on retries (e.g. a flash-sale SKU everyone is buying at
-- once); optimistic (version column) for low-contention data where most
-- concurrent transactions touch DIFFERENT rows and conflicts are rare
-- (e.g. general order updates across a large customer base) -- paying an
-- occasional retry is cheaper than paying for a lock on every write.


-- =============================================================================
-- SECTION E — Deadlocks: a brief, concrete example
-- =============================================================================
-- A deadlock needs at least two transactions each holding a lock the OTHER
-- one is waiting for, forming a cycle.
--
--   SESSION A                              SESSION B
--   --------------------------------       --------------------------------
--   BEGIN;
--   UPDATE stock SET quantity_on_hand = quantity_on_hand - 1
--     WHERE sku = 'WIDGET-1';  -- locks WIDGET-1 row
--                                           BEGIN;
--                                           UPDATE stock SET quantity_on_hand = quantity_on_hand - 1
--                                             WHERE sku = 'GADGET-2';  -- locks GADGET-2 row
--   UPDATE stock SET quantity_on_hand = quantity_on_hand - 1
--     WHERE sku = 'GADGET-2';  -- BLOCKS: B holds this lock
--                                           UPDATE stock SET quantity_on_hand = quantity_on_hand - 1
--                                             WHERE sku = 'WIDGET-1';  -- BLOCKS: A holds this lock
--   -- Both sessions are now waiting on each other forever -> DEADLOCK.
--   -- Postgres's deadlock detector (runs periodically, default every 1s)
--   -- picks ONE transaction as the "victim," kills it with:
--   --   ERROR: deadlock detected
--   -- and lets the other proceed. The victim's application code must catch
--   -- this and retry the whole transaction.
--
-- PREVENTION: always acquire locks on multiple rows in a CONSISTENT,
-- GLOBALLY-AGREED order across every code path (e.g. always lock stock rows
-- sorted by sku ascending, never in whatever order a request happened to
-- list its lines) -- if both sessions above had locked WIDGET-1 before
-- GADGET-2, one would simply have blocked and waited for the other to
-- finish, with no cycle possible.
