# Module 5/8 — Exercises

Do these in order on a machine with JDK 21 + Maven 3.9+ + Docker installed
(none of this can be verified in the sandbox this module was written in).
Start `docker compose up -d` once at the top and leave it running. Verify
each exercise via `curl`/Swagger UI (`http://localhost:8080/swagger-ui.html`)
and by inspecting Postgres directly (`docker compose exec postgres psql -U
orders_app -d orders_db`) and Redis directly (`docker compose exec redis
redis-cli`) where noted.

## 1. (Beginner) Add a `Payment` entity and endpoint

Mirroring java-basics Exercise 1's `Payment` value object, add a
`Payment` `@Entity` (`orderId` FK to `Order`, `amount` as
`BigDecimal`/`NUMERIC(19,2)`, `method` as an `@Enumerated(EnumType.STRING)`
enum: `CREDIT_CARD`, `BANK_TRANSFER`, `CASH_ON_DELIVERY`, `paidAt` as
`Instant`), a `PaymentRepository`, a `PaymentRequest`/`PaymentResponse` DTO
pair, and a `POST /api/v1/orders/{orderId}/payments` endpoint. Add
`@DecimalMin`/`@NotNull` validation matching java-basics' rule ("amount
must be positive" — decide again whether `BigDecimal.ZERO` should be legal,
and justify it in a code comment, exactly as java-basics' exercise asked).

**Check yourself:** should `Payment` be its own top-level `@Entity` with a
FK to `Order`, or should it be a `@OneToMany`/`@OneToOne` NAVIGABLE
association from `Order` (i.e. does `Order` need an `order.getPayments()`
method)? Justify whichever you pick — there's a real trade-off around
whether `Order` needs to know about its payments at all for this module's
current use cases.

## 2. (Beginner) Fix a missing `@Valid`

Temporarily remove the `@Valid` annotation from `OrderRequest.lines` (see
that DTO's Javadoc — it explains exactly what breaks). POST an order with
a line containing `"productId": null`. Confirm you get a 500 (or a worse,
less-clear error) instead of the expected structured 400. Put `@Valid`
back and confirm the same request now returns a clean 400 with a field
error naming the exact nested field. Write one sentence explaining why
Bean Validation's non-cascading-by-default behavior is a real, easy-to-miss
production bug source.

## 3. (Intermediate) Reproduce and fix an N+1 query

Temporarily change `OrderService.getById` to use `orderRepository
.findById(id)` (inherited from `JpaRepository`, no JOIN FETCH) instead of
`findByIdWithLines`. Enable `spring.jpa.show-sql: true` and
`logging.level.org.hibernate.SQL: DEBUG` in `application.yml`. Call
`GET /api/v1/orders/{id}` for an order with 3+ lines and count the SQL
statements logged. Restore `findByIdWithLines` and confirm the query count
drops to one. Write down the exact number of queries you saw in each case
and why.

## 4. (Intermediate) Observe the cache-aside cycle directly in Redis

With `docker compose up -d` running, call `GET /api/v1/products?name=laptop`
to find a product's id, then trigger `ProductService.getStockBySku`
indirectly (add a temporary `GET /api/v1/products/by-sku/{sku}/stock`
endpoint calling it if one doesn't already exist by the time you do this
exercise). Immediately after, run `redis-cli KEYS '*'` and
`redis-cli GET productStock::<sku>` to see the cached value and its TTL
(`redis-cli TTL productStock::<sku>`). Then call the restock or order-
placement endpoint for that SKU and immediately re-run `redis-cli GET
productStock::<sku>` — confirm the key is now GONE (evicted), not updated
to a new value. This is the observable proof of cache-aside vs.
write-through described in `README.md` section 5.

## 5. (Senior) Make the N+1-vs-pagination trade-off concrete

`OrderRepository` currently has no method that both JOIN FETCHes lines AND
paginates. Try to write one:
`@Query("select distinct o from Order o left join fetch o.lines where o.status = :status") Page<Order> findByStatusWithLines(...)`.
Run it against a customer with several orders, each with several lines,
requesting a small page size (e.g. `size=2`). Observe (via
`show-sql`/`DEBUG` logging, and by counting distinct orders actually
returned per page) whether you get exactly 2 orders per page or something
else. Explain in a comment what you observed and why (Hibernate's
in-memory-pagination fallback for fetch-joined `@OneToMany` collections is
the mechanism to research if the result surprises you) — this is exactly
the pitfall `OrderService.listByStatus`'s inline comment warns about
without demonstrating it directly; this exercise is where you demonstrate
it yourself.

## 6. (Scenario) Design a second warehouse

Product wants to support two physical warehouses, each tracking its own
stock for the same products. Using `README.md` section 6's discussion as
your starting point, design (in a comment or a short written note, code
optional but encouraged) the schema change: does `Product.stockQuantity`
get removed entirely in favor of a new `Inventory`/`StockLevel` entity
keyed by `(product_id, warehouse_id)`? What happens to
`ProductService.getStockBySku`'s cache key — does it now need a
`warehouseId` dimension too (`productStock::<sku>::<warehouseId>`)? What
happens to `OrderService.placeOrder` — does an order now need to specify
which warehouse fulfills each line, and if a client doesn't specify one,
what's the fallback logic (nearest warehouse? highest-stock warehouse?
a fixed default)? Write down the migration steps you'd need in order
(schema change, data backfill, application code change, cache
key change) and which ones can safely deploy independently versus which
must land together.
