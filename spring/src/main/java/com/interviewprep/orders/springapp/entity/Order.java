package com.interviewprep.orders.springapp.entity;

import com.interviewprep.orders.springapp.exception.InvalidOrderStateException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The persistence-ready evolution of java-basics' {@code Order} class.
 * Unlike Customer/Product/OrderLine, java-basics' {@code Order} was ALREADY
 * a plain mutable class (not a record) — its Javadoc explains why: an Order
 * has genuine identity and lifecycle-driven mutable state, exactly what a
 * JPA entity needs. The shift here is purely about ADDING persistence
 * annotations to a shape that was already correct, not about a shape
 * change.
 */
@Entity
@Table(name = "orders", uniqueConstraints = @UniqueConstraint(columnNames = "order_number"))
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * A human/API-facing business key ("ORD-<epoch>-<random>" — see
     * {@code OrderService}), separate from the surrogate {@code id}. WHY
     * BOTH: {@code id} is efficient for joins/indexes and never shown to
     * clients (never leak auto-increment ids as the only external
     * identifier — see EXPLANATION.md's IDOR/security note); {@code
     * orderNumber} is what a customer support agent reads over the phone or
     * what appears in a URL/DTO, and can be regenerated with a different
     * scheme later without an ALTER TABLE renumbering every primary key in
     * the system.
     */
    @Column(name = "order_number", nullable = false, unique = true, length = 40)
    private String orderNumber;

    /**
     * Owning side of {@code Customer <-> Order} (holds `customer_id`).
     * LAZY so listing/paginating orders never implicitly joins Customer
     * rows unless a caller actually needs {@code order.getCustomer()} —
     * see OrderLine's Javadoc for the same EAGER-vs-LAZY discussion.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /**
     * Owning side is {@code OrderLine.order}; {@code Order} is the inverse
     * side (mappedBy). {@code cascade = ALL} + {@code orphanRemoval = true}
     * ARE appropriate here (unlike Customer -> Order, see Customer's
     * Javadoc): an {@code OrderLine} has no independent existence or
     * business meaning outside its parent {@code Order} — deleting an Order
     * should delete its lines, and removing a line from {@code order.lines}
     * in Java should delete that row, not just unlink it. This mirrors
     * java-basics' {@code Order} composing {@code OrderLine} with a filled
     * UML diamond (see java-basics/diagrams/domain-model.md) — composition
     * in the OOP sense maps naturally onto cascade+orphanRemoval in JPA.
     *
     * {@code @OrderColumn} persists line order (an extra `line_order`
     * column) so {@code getLines()} returns lines in the order they were
     * added rather than in arbitrary DB row order — without it, a
     * {@code List} association has no guaranteed iteration order once
     * reloaded from the database (only a {@code Set} would be order-
     * agnostic by nature; a List implies order, so JPA needs an explicit
     * column to honor that if you don't want a natural sort like `id ASC`).
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderColumn(name = "line_order")
    private List<OrderLine> lines = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * Optimistic-locking version, same rationale as {@code Product.version}
     * but guarding a different race: two concurrent requests trying to
     * transition the SAME order's status (e.g. one confirming, one
     * cancelling) should not both silently "win" — whichever transaction
     * commits second should fail with an optimistic-lock exception rather
     * than overwrite the first transition's effects.
     */
    @Version
    private Long version;

    protected Order() {
        // required by JPA
    }

    public Order(String orderNumber, Customer customer) {
        if (orderNumber == null || orderNumber.isBlank()) {
            throw new IllegalArgumentException("orderNumber must not be blank");
        }
        if (customer == null) {
            throw new IllegalArgumentException("Order requires a customer");
        }
        this.orderNumber = orderNumber;
        this.customer = customer;
    }

    /**
     * WHY THIS METHOD SETS BOTH SIDES of the association (adds to
     * {@code this.lines} AND calls {@code line.setOrder(this)}): JPA
     * relationships are tracked via the database foreign key column, which
     * lives on {@code OrderLine} (the owning side). If callers only did
     * {@code order.getLinesInternal().add(line)} without also setting
     * {@code line.order}, Hibernate would persist the new {@code OrderLine}
     * row with a NULL {@code order_id} — the Java-side object graph would
     * look right in memory but be silently wrong in the database. Every
     * bidirectional JPA association needs exactly one method like this
     * that keeps both sides consistent; scattering
     * {@code line.setOrder(x)} calls across multiple call sites instead is
     * a common source of "it works until it doesn't" bugs.
     */
    public void addLine(OrderLine line) {
        lines.add(line);
        line.setOrder(this);
    }

    /** Same defensive-copy discipline as java-basics' Order.getLines(). */
    public List<OrderLine> getLines() {
        return List.copyOf(lines);
    }

    public BigDecimal totalAmount() {
        return lines.stream()
                .map(OrderLine::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Identical delegation pattern to java-basics' {@code Order.transitionTo}
     * — legality lives on {@code OrderStatus}, not here — but now throws
     * this module's {@code InvalidOrderStateException} (see that class's
     * Javadoc for why a dedicated type replaces the plain
     * {@code IllegalStateException} java-basics used).
     */
    public void transitionTo(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidOrderStateException(
                    "Cannot transition order %s from %s to %s".formatted(orderNumber, status, next));
        }
        status = next;
    }

    public Long getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public Customer getCustomer() {
        return customer;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Order other)) {
            return false;
        }
        return Objects.equals(orderNumber, other.orderNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(orderNumber);
    }

    @Override
    public String toString() {
        return "Order[id=%s, orderNumber=%s, status=%s, lines=%d]"
                .formatted(id, orderNumber, status, lines.size());
    }
}
