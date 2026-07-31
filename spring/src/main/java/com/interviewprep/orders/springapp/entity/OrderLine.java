package com.interviewprep.orders.springapp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * The persistence-ready evolution of java-basics' {@code OrderLine} record.
 *
 * WHY A CLASS, NOT A RECORD: same reasoning as {@code Customer}/{@code Product}
 * (see Customer's Javadoc for the full explanation) — needs a no-arg
 * constructor, a generated {@code id}, and a back-reference to its owning
 * {@code Order} that Hibernate sets after construction.
 *
 * WHY {@code unitPriceAtOrderTime} EXISTS NOW (it didn't in java-basics):
 * java-basics' {@code OrderLine.lineTotal()} Javadoc explicitly flagged this
 * as a PRODUCTION NOTE — "in a real system, prices change over time... an
 * order line should snapshot the price paid, not re-look-up the product's
 * current price." This module is where that note gets implemented: the
 * price is copied from {@code Product.getPrice()} at the moment the line is
 * created (see {@code OrderService.placeOrder}) and stored directly on the
 * {@code OrderLine} row. Without this, a price change to a Product next
 * month would silently rewrite the historical total of every past order
 * containing it — a real accounting/audit bug, not a hypothetical one.
 */
@Entity
@Table(name = "order_lines")
public class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owning side of the {@code Order <-> OrderLine} relationship (holds the
     * {@code order_id} foreign key). {@code FetchType.LAZY} is used
     * (rather than JPA's default of EAGER for {@code @ManyToOne}) so
     * loading an {@code OrderLine} never implicitly triggers loading its
     * parent {@code Order} unless something actually navigates
     * {@code orderLine.getOrder()} — avoiding an easy-to-miss N+1/over-
     * fetch source. This is a very common "gotcha" interview question:
     * {@code @ManyToOne} and {@code @OneToOne} default to EAGER;
     * {@code @OneToMany} and {@code @ManyToMany} default to LAZY — most teams
     * override the EAGER defaults to LAZY everywhere and fetch explicitly
     * (see {@code OrderRepository.findByIdWithLines}'s JOIN FETCH) when
     * they actually need the association, rather than relying on defaults.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPriceAtOrderTime;

    protected OrderLine() {
        // required by JPA
    }

    public OrderLine(Product product, int quantity, BigDecimal unitPriceAtOrderTime) {
        if (product == null) {
            throw new IllegalArgumentException("OrderLine requires a product");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        if (unitPriceAtOrderTime == null || unitPriceAtOrderTime.signum() < 0) {
            throw new IllegalArgumentException("unitPriceAtOrderTime must be zero or positive");
        }
        this.product = product;
        this.quantity = quantity;
        this.unitPriceAtOrderTime = unitPriceAtOrderTime;
    }

    /** Package-visible: only {@code Order.addLine()} should set this, to
     * keep both sides of the bidirectional association in sync (see
     * Order.addLine's Javadoc). */
    void setOrder(Order order) {
        this.order = order;
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public Product getProduct() {
        return product;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPriceAtOrderTime() {
        return unitPriceAtOrderTime;
    }

    /** Same computation as java-basics' OrderLine.lineTotal(), now against
     * the SNAPSHOT price rather than the product's current price. */
    public BigDecimal lineTotal() {
        return unitPriceAtOrderTime.multiply(BigDecimal.valueOf(quantity));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrderLine other)) {
            return false;
        }
        // id-based equality is acceptable here (unlike Customer/Product):
        // OrderLine has no natural business key of its own — it's identity-
        // less outside the context of its Order until persisted, so we fall
        // back to reference equality for transient instances and id equality
        // once persisted.
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "OrderLine[id=%s, product=%s, quantity=%d, unitPrice=%s]"
                .formatted(id, product != null ? product.getSku() : null, quantity, unitPriceAtOrderTime);
    }
}
