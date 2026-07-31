package com.interviewprep.orders.springapp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import com.interviewprep.orders.springapp.exception.InsufficientStockException;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The persistence-ready evolution of java-basics' {@code Product} record —
 * PLUS the stock-tracking responsibility that java-basics deliberately kept
 * in a separate {@code Inventory} class.
 *
 * ============================================================================
 * DESIGN DECISION: INVENTORY FOLDED INTO {@code Product.stockQuantity},
 * NOT A SEPARATE {@code Inventory} ENTITY/TABLE. Justification (see
 * spring/README.md "Inventory: entity vs. field" for the full write-up):
 * ============================================================================
 *
 * java-basics' {@code Inventory} class is a {@code Map<String, Integer>}
 * (SKU -> quantity) deliberately decoupled from {@code Order}/{@code Product}
 * — a fine design for a single in-memory process where "decoupled" mostly
 * means "a different Java class." In a relational schema, decoupling stock
 * from products only pays for itself when there is a genuine additional
 * dimension to the data — most commonly **multiple warehouses/locations**
 * (stock becomes a function of (product, warehouse), i.e. a proper
 * {@code inventory_items} table with its own primary key and a composite
 * unique constraint on {@code (product_id, warehouse_id)}), or when stock
 * needs its OWN audit trail / write frequency independent of product
 * metadata changes (e.g. a high-throughput stock-ledger table recording
 * every reservation/release as an immutable event, which a mature system
 * would add ON TOP of a simple quantity column, not instead of it).
 *
 * This module has exactly one implicit warehouse and no requirement to
 * audit individual stock movements as separate rows, so a separate
 * `inventory` table would add a join to every stock check and an extra
 * foreign key to manage for zero real benefit — normalization for its own
 * sake, not because a real query or constraint needs it. Folding stock into
 * {@code Product.stockQuantity} is the simpler, equally correct choice
 * *for this schema's actual requirements*.
 *
 * THE HONEST TRADE-OFF: if this were a real multi-warehouse system, this
 * decision would be wrong on day one, and retrofitting a separate
 * {@code Inventory} entity later means a real migration (new table, backfill
 * existing `stockQuantity` values as warehouse="DEFAULT" rows, update every
 * query and the cache-aside logic in `ProductService`). Call this out
 * explicitly in a design review rather than silently assuming single-
 * warehouse forever — "premature generalization" (an unneeded Inventory
 * table now) and "premature specialization" (baking in single-warehouse
 * assumptions that bite later) are both real risks, and this module
 * deliberately picks the simpler one because the spec's scope doesn't call
 * for multi-warehouse — a decision worth being able to defend, not a
 * default to apply blindly.
 *
 * WHY {@code @Version} (OPTIMISTIC LOCKING) ON THIS ENTITY SPECIFICALLY:
 * {@code stockQuantity} is exactly the "read-then-check-then-write" field
 * java-basics' {@code Inventory.reserve()} Javadoc flags as a race
 * condition under concurrent access (two threads both read stock=1, both
 * think there's enough for their order, both decrement — one sale oversells
 * the other). {@code @Version} makes Hibernate include `WHERE version = ?`
 * on every UPDATE and bump the version column atomically; if a concurrent
 * transaction already changed the row (and its version) since this
 * transaction read it, the UPDATE affects zero rows and Hibernate throws
 * {@code OptimisticLockException} (wrapped by Spring Data as
 * {@code ObjectOptimisticLockingFailureException}) instead of silently
 * overwriting a concurrent change. This ties forward directly into the
 * database/ module's optimistic-vs-pessimistic locking discussion:
 * optimistic locking (used here) assumes conflicts are RARE and pays the
 * cost only when one actually happens (a failed update + a retry/error
 * response); pessimistic locking ({@code SELECT ... FOR UPDATE}) assumes
 * conflicts are COMMON and pays a lock-acquisition cost on every read,
 * serializing access up front. High-read/low-contention stock checks (this
 * use case, most of the time) favor optimistic; a flash-sale on one
 * extremely hot SKU is exactly the scenario where pessimistic locking (or a
 * queue-based approach entirely, see system-design/) starts to win.
 */
@Entity
@Table(name = "products", uniqueConstraints = @UniqueConstraint(columnNames = "sku"))
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    @Column(nullable = false, length = 200)
    private String name;

    /**
     * WHY {@code BigDecimal} AND {@code columnDefinition/precision} — same
     * reasoning as java-basics' Product.price Javadoc (binary floating
     * point cannot represent most decimal fractions exactly). The
     * additional JPA-specific point: {@code precision = 19, scale = 2}
     * maps to a DB-level {@code NUMERIC(19,2)} / {@code DECIMAL(19,2)}
     * column — the database itself enforces exact decimal storage, not
     * just the Java-side type. Never let Hibernate infer a money column's
     * type without specifying precision/scale explicitly; the default can
     * silently be too narrow for large values or truncate scale.
     */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int stockQuantity;

    /**
     * See the class-level Javadoc for the full optimistic-locking
     * rationale. {@code Long} (not primitive {@code long}) is the
     * convention because a not-yet-persisted entity needs a null-able
     * "no version yet" state before its first insert.
     */
    @Version
    private Long version;

    protected Product() {
        // required by JPA — see Customer's Javadoc for the full reasoning.
    }

    public Product(String sku, String name, BigDecimal price, int initialStockQuantity) {
        this.sku = requireNonBlank(sku, "sku");
        this.name = requireNonBlank(name, "name");
        this.price = requireNonNegative(price);
        this.stockQuantity = requireNonNegativeStock(initialStockQuantity);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static BigDecimal requireNonNegative(BigDecimal price) {
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("price must be zero or positive: " + price);
        }
        return price;
    }

    private static int requireNonNegativeStock(int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("stockQuantity must not be negative: " + quantity);
        }
        return quantity;
    }

    /**
     * Decrements stock, enforcing the same "never go negative" invariant
     * java-basics' {@code Inventory.reserve()} enforces — the invariant
     * moved from a standalone class's method onto the entity itself, which
     * is a common (though not universal — see the "anemic domain model"
     * discussion in EXPLANATION.md) place to put it once the entity IS the
     * stock record. Throws the module's own {@code InsufficientStockException}
     * (see exception package) rather than mutating and letting a DB
     * CHECK constraint reject it — failing fast in Java gives a much more
     * actionable error than a generic constraint-violation SQL exception.
     */
    public void decrementStock(int quantity, String skuForError) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity to decrement must be positive: " + quantity);
        }
        if (stockQuantity < quantity) {
            throw new InsufficientStockException(skuForError, quantity, stockQuantity);
        }
        stockQuantity -= quantity;
    }

    /** Returns previously reserved stock (order cancellation, rollback path). */
    public void restock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("restock quantity must be positive: " + quantity);
        }
        stockQuantity += quantity;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = requireNonBlank(name, "name");
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = requireNonNegative(price);
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Product other)) {
            return false;
        }
        return Objects.equals(sku, other.sku);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(sku);
    }

    @Override
    public String toString() {
        return "Product[id=%s, sku=%s, name=%s, price=%s, stock=%d]"
                .formatted(id, sku, name, price, stockQuantity);
    }
}
