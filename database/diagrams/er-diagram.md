# Entity-Relationship Diagram — Order/Inventory Schema

This is the relational shape of `sql/schema.sql`, matching the same
Order/Inventory domain modeled as plain Java objects in
`java-basics/src/main/java/com/interviewprep/orders/domain` (see
`java-basics/diagrams/domain-model.md` for the class-diagram equivalent).
Read both side by side — every table below corresponds to exactly one Java
type, plus the `stock` table standing in for `Inventory`'s internal map.

```mermaid
erDiagram
    CUSTOMERS ||--o{ ORDERS : "places"
    ORDERS ||--o{ ORDER_LINES : "composed of"
    PRODUCTS ||--o{ ORDER_LINES : "referenced by"
    PRODUCTS ||--|| STOCK : "tracked by"

    CUSTOMERS {
        varchar customer_id PK
        varchar name
        varchar email UK
        timestamptz created_at
    }

    PRODUCTS {
        varchar sku PK
        varchar name
        numeric price
    }

    STOCK {
        varchar sku PK_FK
        int quantity_on_hand
        int version
        timestamptz updated_at
    }

    ORDERS {
        varchar order_id PK
        varchar customer_id FK
        varchar status
        numeric total_amount
        timestamptz created_at
        int version
    }

    ORDER_LINES {
        bigint order_line_id PK
        varchar order_id FK
        varchar sku FK
        int quantity
        numeric unit_price
        numeric line_total
    }
```

## Reading the cardinalities

- `CUSTOMERS ||--o{ ORDERS` — one customer places zero-or-many orders; an
  order belongs to exactly one customer (`orders.customer_id NOT NULL`).
- `ORDERS ||--o{ ORDER_LINES` — one order is composed of zero-or-many order
  lines (`ON DELETE CASCADE`: deleting an order deletes its lines — matches
  the filled-diamond "composition" relationship in the Java class diagram,
  where an `OrderLine` cannot outlive its `Order`).
- `PRODUCTS ||--o{ ORDER_LINES` — one product can appear on many order
  lines across many orders; deleting a referenced product is blocked
  (`ON DELETE RESTRICT`) to protect historical order data.
- `PRODUCTS ||--|| STOCK` — a strict one-to-one: every product has exactly
  one stock row tracking its current quantity on hand. This mirrors
  `Inventory`'s `Map<String sku, Integer quantity>` in Java, just
  normalized into its own table instead of an in-memory map.

## Deliberate differences from the Java object graph

- **`stock` has no Java equivalent as a *class*** — it's the relational
  form of `Inventory`'s private `stockBySku` map. In Java that map lives
  inside the `Inventory` object; in SQL it must be its own table because
  relational databases model "a map from key to value" as "a table with
  the key as a (foreign) primary key."
- **`order_lines` has a surrogate primary key (`order_line_id`)** that
  `OrderLine` the Java record does not — a record has no identity beyond
  its fields, but every database row needs one for individual
  addressability (see `schema.sql`'s comment on this).
- **`unit_price` is a snapshot, not a live reference to `products.price`**
  — this is the SQL expression of the "PRODUCTION NOTE" already present in
  `OrderLine.java`'s Javadoc about not re-deriving historical prices from
  the current product price.
