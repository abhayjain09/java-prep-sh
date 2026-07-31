-- =============================================================================
-- database/sql/schema.sql
-- Module 7 — Databases: canonical relational schema for the Order/Inventory
-- domain used across the whole repo (see java-basics/src/.../domain/*.java).
--
-- TARGET DIALECT: PostgreSQL 14+ (primary). Oracle-specific callouts are noted
-- inline with "ORACLE:" comments; see database/README.md section "PostgreSQL
-- vs Oracle" for the full side-by-side.
--
-- ENVIRONMENT NOTE: there is no live PostgreSQL/Oracle engine in this sandbox.
-- This file has NOT been executed against a real server. It is written to be
-- syntactically valid PostgreSQL and reviewed by a human; if you want to
-- actually run it, spin up `postgres:16` locally or on RDS and pipe this file
-- into `psql`.
--
-- MAPPING TO JAVA DOMAIN (java-basics/src/main/java/com/interviewprep/orders):
--   Customer(id, name, email)                -> customers
--   Product(sku, name, price)                -> products
--   Inventory(stockBySku: Map<String,Integer>) -> stock
--   Order(id, customer, lines, status)       -> orders
--   OrderLine(product, quantity)             -> order_lines (+ price snapshot)
--   OrderStatus enum                         -> orders.status (CHECK-constrained)
--
-- See diagrams/er-diagram.md for the visual and EXPLANATION.md for the
-- section-by-section walkthrough of every design decision below.
-- =============================================================================

-- Dropped in reverse dependency order so this file can be re-run idempotently
-- while you're experimenting (CASCADE also drops dependent indexes/FKs).
-- PRODUCTION NOTE: you would never ship DROP TABLE statements in a real
-- migration — this is a teaching/scratch script, not a Flyway/Liquibase
-- migration. Real schema changes are additive, versioned, and reversible.
DROP TABLE IF EXISTS order_lines CASCADE;
DROP TABLE IF EXISTS orders CASCADE;
DROP TABLE IF EXISTS stock CASCADE;
DROP TABLE IF EXISTS products CASCADE;
DROP TABLE IF EXISTS customers CASCADE;

-- =============================================================================
-- customers
-- Mirrors domain/Customer.java: record Customer(String id, String name, String email)
-- =============================================================================
CREATE TABLE customers (
    -- WHY VARCHAR PK MATCHING THE APP-LEVEL ID, NOT A SURROGATE BIGINT:
    -- Customer.id is already a String identifier assigned by the application
    -- layer (see Customer's compact constructor: "id must not be blank").
    -- Using that same value as the DB primary key avoids a second identifier
    -- system (natural key from the app's perspective). If customer IDs were
    -- ever *generated* by the database instead (they aren't here), a
    -- GENERATED ALWAYS AS IDENTITY surrogate key would be preferred — see the
    -- "orders" table below for that pattern, and the PostgreSQL-vs-Oracle
    -- section in README.md for IDENTITY vs SEQUENCE differences.
    customer_id   VARCHAR(64)   PRIMARY KEY,

    name          VARCHAR(200)  NOT NULL,

    -- Customer's compact constructor checks `email.contains("@")`. The CHECK
    -- below is a coarse SQL-level mirror of that same guard — it is NOT a
    -- substitute for real email validation (which belongs in the app/service
    -- layer, same as in Java); it exists purely to demonstrate defense in
    -- depth: even a buggy or bypassed application layer cannot insert an
    -- email with no '@' at all.
    email         VARCHAR(255)  NOT NULL,

    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_customers_email     UNIQUE (email),
    CONSTRAINT chk_customers_email_at CHECK (email LIKE '%_@_%')
);

COMMENT ON TABLE  customers IS 'One row per Customer (see domain/Customer.java). Natural key = app-assigned customer_id.';
COMMENT ON COLUMN customers.email IS 'UNIQUE mirrors a real business rule (one account per email); CHECK is a shallow shape guard only.';

-- =============================================================================
-- products
-- Mirrors domain/Product.java: record Product(String sku, String name, BigDecimal price)
-- =============================================================================
CREATE TABLE products (
    -- SKU is the natural key here too — exactly like Customer.id, it's
    -- assigned upstream (by the catalog/PIM system in a real company, not by
    -- this database), so it is the primary key rather than a synthetic one.
    sku           VARCHAR(64)    PRIMARY KEY,

    name          VARCHAR(300)   NOT NULL,

    -- WHY NUMERIC, NOT FLOAT/DOUBLE PRECISION: same reasoning as Product.java
    -- using BigDecimal instead of double — NUMERIC in Postgres is an exact
    -- decimal type (arbitrary precision, no binary-fraction rounding error).
    -- FLOAT/DOUBLE PRECISION are binary floating point, exactly the trap
    -- Product.java's Javadoc warns about in Java. NUMERIC(12,2) = up to 10
    -- integer digits + 2 decimal places, plenty for unit prices.
    -- ORACLE: the equivalent type is NUMBER(12,2) — conceptually identical
    -- exact decimal arithmetic, different keyword.
    price         NUMERIC(12,2)  NOT NULL,

    CONSTRAINT chk_products_price_nonneg CHECK (price >= 0)
);

COMMENT ON TABLE products IS 'One row per Product (see domain/Product.java). price mirrors BigDecimal price with the same non-negative invariant.';

-- =============================================================================
-- stock
-- Mirrors domain/Inventory.java: Map<String sku, Integer quantity>
-- =============================================================================
CREATE TABLE stock (
    sku               VARCHAR(64)  PRIMARY KEY
                       REFERENCES products (sku)
                       ON DELETE RESTRICT,   -- can't delete a product that still has a stock row

    -- Directly mirrors the one invariant Inventory.java enforces in Java:
    -- "stock never negative" (see reserve()/release()/restock()). Here the
    -- CHECK constraint enforces it at the database level too — this matters
    -- because the database is the last line of defense if some other
    -- process (a batch job, a manual `UPDATE stock ...`, a bug in a
    -- different service) writes to this table without going through the
    -- Java Inventory class at all.
    quantity_on_hand  INT          NOT NULL DEFAULT 0,

    -- OPTIMISTIC LOCKING COLUMN: incremented on every UPDATE. See
    -- transactions-and-locking.sql for the WHERE version = ? pattern this
    -- column exists to support, as an alternative to SELECT ... FOR UPDATE.
    version           INT          NOT NULL DEFAULT 0,

    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_stock_qty_nonneg CHECK (quantity_on_hand >= 0)
);

COMMENT ON TABLE stock IS 'One row per SKU tracking quantity on hand — DB-level mirror of domain/Inventory.java''s stockBySku map, with the "never negative" invariant enforced by a CHECK constraint rather than only in application code.';
COMMENT ON COLUMN stock.version IS 'Optimistic-locking version counter — see transactions-and-locking.sql.';

-- =============================================================================
-- orders
-- Mirrors domain/Order.java: id, customer, status (+ derived totalAmount())
-- =============================================================================
CREATE TABLE orders (
    -- WHY VARCHAR PK HERE TOO: OrderService.placeOrder() builds the id in
    -- application code as "ORD-" + orderIdSequence.getAndIncrement() (an
    -- AtomicLong). That's a deliberate Module 1 teaching choice (showing a
    -- hand-rolled, non-DB-backed ID generator) — in a real system this
    -- would typically flip: let the DATABASE generate the identifier via
    -- GENERATED ALWAYS AS IDENTITY (surrogate BIGINT), and treat the
    -- "ORD-123" display format as a derived, formatted view of that number,
    -- not the primary key itself. We keep VARCHAR here to stay faithful to
    -- the existing Java code's id shape; the commented-out alternative below
    -- shows the surrogate-key version for comparison.
    order_id      VARCHAR(40)    PRIMARY KEY,

    -- -- ALTERNATIVE (surrogate key, arguably more idiomatic for DB-generated
    -- -- order numbers than an app-generated string):
    -- order_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- order_code VARCHAR(40) GENERATED ALWAYS AS ('ORD-' || order_id) STORED,
    -- ORACLE 12c+: IDENTITY columns exist natively too:
    --   order_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY
    -- ORACLE <12c: no IDENTITY keyword — you hand-roll it with
    --   CREATE SEQUENCE orders_seq; ... INSERT ... VALUES (orders_seq.NEXTVAL, ...)
    -- or a BEFORE INSERT trigger that calls the sequence. See README.md's
    -- PostgreSQL-vs-Oracle section for the full comparison.

    customer_id   VARCHAR(64)    NOT NULL
                  REFERENCES customers (customer_id)
                  ON DELETE RESTRICT,        -- don't let a customer disappear out from under existing orders

    -- WHY A CHECK-CONSTRAINED VARCHAR RATHER THAN A NATIVE ENUM TYPE:
    -- Postgres supports a native CREATE TYPE ... AS ENUM (see the commented
    -- block below). We default to VARCHAR + CHECK here because:
    --   1. Adding a new legal value later is a single, ordinary
    --      `ALTER TABLE ... DROP CONSTRAINT ...; ALTER TABLE ... ADD
    --      CONSTRAINT ...` — a plain DDL statement like any other.
    --   2. A native ENUM requires `ALTER TYPE order_status ADD VALUE ...`,
    --      which (a) cannot run inside a transaction block together with
    --      other statements that use the new value in the same transaction
    --      before PostgreSQL 12, and (b) can NEVER remove or reorder a
    --      value once added — enum value sets are append-only in Postgres.
    --   3. VARCHAR is portable to Oracle (which has no native enum type at
    --      all — Oracle models this exactly the same way, as a
    --      CHECK-constrained VARCHAR2), so this table definition needs zero
    --      changes to run on either engine.
    -- The trade-off: a native ENUM is slightly more storage-efficient (4
    -- bytes, stored as an OID-like value) and self-documents the legal
    -- values via \dT+ / catalog introspection. For a status column with a
    -- handful of values that may legitimately grow (see java-basics
    -- EXERCISES.md #6, adding RETURNED), the flexibility of VARCHAR + CHECK
    -- wins for this teaching example. See README.md for the full discussion.
    status        VARCHAR(20)    NOT NULL DEFAULT 'PENDING',

    -- -- NATIVE ENUM ALTERNATIVE (commented out — not used by this schema):
    -- CREATE TYPE order_status AS ENUM
    --     ('PENDING', 'CONFIRMED', 'SHIPPED', 'DELIVERED', 'CANCELLED');
    -- ... then in the table: status order_status NOT NULL DEFAULT 'PENDING',

    -- Denormalized/cached total. WHY STORE IT AT ALL, WHEN totalAmount() in
    -- Java is a derived, computed value (sum of line totals)? Two reasons
    -- interviewers like to probe:
    --   1. Reporting/dashboard queries ("top customers by spend this
    --      quarter") over millions of rows are far cheaper reading one
    --      indexed NUMERIC column than re-joining and re-summing
    --      order_lines every time.
    --   2. It lets you index and filter/sort on total_amount directly (see
    --      indexing.sql).
    -- The COST: this is now a value that can drift from the true sum of its
    -- order_lines if a line is added/changed without updating this column —
    -- a classic normalization-vs-denormalization trade-off. In production
    -- this is usually kept in sync either via application-code discipline
    -- inside the same transaction (what sample-queries.sql / transactions
    -- file do here) or a trigger that recomputes it on order_lines changes.
    total_amount  NUMERIC(14,2)  NOT NULL DEFAULT 0
                  CHECK (total_amount >= 0),

    -- Partition key used in partitioning-example.sql. Kept here (rather than
    -- only in the partitioned variant) so sample-queries.sql's window
    -- functions (LAG/LEAD "previous order date", running totals ordered by
    -- date) have a real column to order by.
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),

    -- OPTIMISTIC LOCKING COLUMN — see transactions-and-locking.sql. Mirrors
    -- the idea of JPA's @Version (Module 5/Hibernate territory) at the raw
    -- SQL level.
    version       INT            NOT NULL DEFAULT 0,

    CONSTRAINT chk_orders_status CHECK (
        status IN ('PENDING', 'CONFIRMED', 'SHIPPED', 'DELIVERED', 'CANCELLED')
    )
    -- NOTE: this list must stay in sync with domain/OrderStatus.java's enum
    -- constants by hand, since VARCHAR + CHECK has no compiler to enforce
    -- exhaustiveness the way the Java enum's switch expression does (see
    -- java-basics/README.md section "OOP" and EXERCISES.md #6 — this is
    -- the concrete cost of losing that compile-time guarantee at the SQL
    -- layer, an excellent point to raise in an interview when discussing
    -- this VARCHAR-vs-ENUM trade-off).
);

COMMENT ON TABLE  orders IS 'One row per Order (see domain/Order.java). status is CHECK-constrained rather than a native Postgres ENUM — see inline comment and README.md for the trade-off.';
COMMENT ON COLUMN orders.total_amount IS 'Denormalized cache of SUM(order_lines.line_total) for this order — see inline comment for the normalization trade-off.';
COMMENT ON COLUMN orders.version IS 'Optimistic-locking version counter, incremented on every UPDATE — see transactions-and-locking.sql.';

-- =============================================================================
-- order_lines
-- Mirrors domain/OrderLine.java: record OrderLine(Product product, int quantity)
-- =============================================================================
CREATE TABLE order_lines (
    -- OrderLine itself has no identity in Java (it's a record — value
    -- semantics, no id field) but a database ROW needs a primary key to be
    -- individually addressable (for UPDATE/DELETE of a single line, and for
    -- other tables to reference it later if needed). GENERATED ALWAYS AS
    -- IDENTITY is the modern (SQL:2003 standard, Postgres 10+) way to get an
    -- auto-incrementing surrogate key — prefer it over the older
    -- `SERIAL` pseudo-type, which is really just sugar over a manually
    -- created sequence + DEFAULT and has some ownership/permission quirks
    -- IDENTITY avoids.
    order_line_id  BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    order_id       VARCHAR(40)   NOT NULL
                    REFERENCES orders (order_id)
                    ON DELETE CASCADE,   -- deleting an order deletes its lines — an order line cannot outlive its order (matches the UML "composition" filled-diamond relationship in java-basics/diagrams/domain-model.md)

    sku            VARCHAR(64)   NOT NULL
                    REFERENCES products (sku)
                    ON DELETE RESTRICT,  -- don't allow deleting a product that's referenced by historical order lines

    -- Mirrors OrderLine's compact constructor: "quantity must be positive".
    quantity       INT           NOT NULL,

    -- PRICE SNAPSHOT — see OrderLine.java's own "PRODUCTION NOTE" Javadoc:
    -- "an order line should snapshot the price paid, not re-look-up the
    -- product's *current* price on every totalAmount() call". This column
    -- IS that snapshot: it is copied from products.price at the moment the
    -- order is placed (see transactions-and-locking.sql for the INSERT that
    -- does this) and never changes afterward, even if products.price later
    -- changes. Without this column, a price increase next month would
    -- silently rewrite the historical total of every past order the moment
    -- you joined to the (now-changed) products table — a real accounting
    -- bug, not a hypothetical one.
    unit_price     NUMERIC(12,2) NOT NULL,

    -- GENERATED (COMPUTED) COLUMN: Postgres computes and stores this value
    -- automatically from quantity * unit_price on every INSERT/UPDATE; you
    -- cannot write to it directly (INSERT ... will error if you try to
    -- supply a value). This is the SQL-level equivalent of
    -- OrderLine.lineTotal() in Java — same computation, expressed once,
    -- can't drift out of sync because the engine — not application code —
    -- recomputes it whenever the inputs change.
    -- ORACLE: equivalent syntax is
    --   line_total NUMBER(14,2) GENERATED ALWAYS AS (quantity * unit_price) VIRTUAL
    -- (Oracle calls it a "virtual column"; conceptually identical).
    line_total     NUMERIC(14,2) GENERATED ALWAYS AS (quantity * unit_price) STORED,

    CONSTRAINT chk_order_lines_qty_positive   CHECK (quantity > 0),
    CONSTRAINT chk_order_lines_price_nonneg   CHECK (unit_price >= 0)
);

COMMENT ON TABLE  order_lines IS 'One row per OrderLine (see domain/OrderLine.java). unit_price is a snapshot taken at order-placement time, never re-derived from products.price.';
COMMENT ON COLUMN order_lines.line_total IS 'STORED generated column: quantity * unit_price, recomputed automatically by Postgres — SQL-level equivalent of OrderLine.lineTotal().';

-- =============================================================================
-- A NOTE ON WHAT'S DELIBERATELY MISSING FROM THIS SCHEMA
-- =============================================================================
-- No index-creation statements live in this file on purpose — they're kept
-- in indexing.sql so schema (structure + constraints) and performance
-- tuning (indexes) are easy to reason about separately, which mirrors how
-- most real migration tooling separates "create the table" migrations from
-- "add an index" migrations (the latter often run CONCURRENTLY in
-- production, which cannot happen inside the same transaction as a CREATE
-- TABLE). One important consequence covered in indexing.sql: PostgreSQL
-- automatically creates an index for a PRIMARY KEY or UNIQUE constraint,
-- but it does NOT automatically index foreign-key columns (customer_id on
-- orders, order_id and sku on order_lines) — that surprises a lot of
-- engineers coming from other databases/ORMs and is exactly why
-- indexing.sql adds those explicitly.
