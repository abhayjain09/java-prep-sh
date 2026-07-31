package com.interviewprep.orders.patterns.structural.composite;

import com.interviewprep.orders.domain.Product;
import com.interviewprep.orders.patterns.behavioral.visitor.OrderComponentVisitor;

import java.math.BigDecimal;

/**
 * The COMPOSITE pattern's LEAF: wraps a single domain {@link Product} so it
 * can participate in an {@link OrderComponent} tree alongside
 * {@link ProductBundle} composites. A leaf has no children — {@code price()}
 * simply returns the wrapped product's own price.
 */
public final class ProductLeaf implements OrderComponent {

    private final Product product;

    public ProductLeaf(Product product) {
        this.product = product;
    }

    @Override
    public BigDecimal price() {
        return product.price();
    }

    @Override
    public String name() {
        return product.name();
    }

    public Product product() {
        return product;
    }

    @Override
    public <R> R accept(OrderComponentVisitor<R> visitor) {
        // Double dispatch: which visit(...) overload runs is resolved by
        // BOTH the visitor's runtime type AND this leaf's runtime type,
        // which is what lets visitors branch on element type without a
        // single instanceof check anywhere.
        return visitor.visit(this);
    }
}
