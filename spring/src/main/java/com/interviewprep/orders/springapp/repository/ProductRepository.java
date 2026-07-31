package com.interviewprep.orders.springapp.repository;

import com.interviewprep.orders.springapp.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /** DERIVED QUERY METHOD — Spring Data generates
     * {@code WHERE sku = ?} from the method name. */
    Optional<Product> findBySku(String sku);

    /**
     * DERIVED QUERY METHOD + PAGEABLE — used by the "search products by
     * name" filtering example in {@code ProductController}. Spring Data
     * appends {@code LIMIT}/{@code OFFSET} (or the DB-appropriate
     * equivalent) and an {@code ORDER BY} clause automatically from the
     * {@code Pageable}'s page/size/sort, and ALSO runs a second
     * {@code COUNT(*)} query to populate {@code Page.getTotalElements()} —
     * worth knowing that a paginated query is actually two queries under
     * the hood, which matters when reasoning about the cost of a
     * paginated endpoint under load.
     */
    Page<Product> findByNameContainingIgnoreCase(String namePart, Pageable pageable);

    /**
     * EXPLICIT JPQL VIA {@code @Query} — needed here because "products with
     * stock below a threshold" isn't a simple property match; it's a
     * range comparison Spring Data's method-name parser can express too
     * (e.g. {@code findByStockQuantityLessThan}), but this method
     * demonstrates the {@code @Query} escape hatch deliberately, since
     * real query methods often need conditions/joins/projections derived
     * query methods can't express at all (e.g. joining to Order to find
     * "products that have never been ordered"). JPQL queries against the
     * ENTITY model (Product, not the `products` table/columns) — Hibernate
     * translates it to SQL at runtime, which is why {@code p.stockQuantity}
     * (the Java field name) is used here, not {@code stock_quantity} (the
     * DB column name).
     */
    @Query("select p from Product p where p.stockQuantity < :threshold order by p.stockQuantity asc")
    List<Product> findLowStock(@Param("threshold") int threshold);
}
