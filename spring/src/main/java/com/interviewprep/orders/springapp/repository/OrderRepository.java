package com.interviewprep.orders.springapp.repository;

import com.interviewprep.orders.springapp.entity.Order;
import com.interviewprep.orders.springapp.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@code Order}. Demonstrates all three
 * query-definition styles this module's spec calls for: a derived query
 * method, an explicit {@code @Query} using JPQL, and a {@code Pageable}
 * based paginated finder (several methods below combine more than one of
 * these at once).
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /** DERIVED QUERY METHOD — looks up by the business key (see
     * Order.orderNumber's Javadoc for why it's separate from the
     * surrogate {@code id}). */
    Optional<Order> findByOrderNumber(String orderNumber);

    /**
     * DERIVED QUERY METHOD + PAGEABLE — filtering orders by status is the
     * single most common "worklist" query in an order-management system
     * ("show me all PENDING orders needing fulfillment"), so it's worth a
     * dedicated method rather than a generic dynamic-filter mechanism
     * (Specifications/Querydsl — mentioned in EXPLANATION.md as the
     * escape hatch once you have many optional filter combinations).
     */
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    /**
     * EXPLICIT JPQL WITH {@code JOIN FETCH} — THE FIX FOR N+1. Without the
     * {@code join fetch}, loading an Order and then accessing
     * {@code order.getLines()} (LAZY) fires one additional SELECT per order
     * line access; accessing each line's {@code getProduct()} (also LAZY)
     * fires ANOTHER SELECT per line. For an order with 5 lines, that's 1
     * (order) + 1 (lines) + 5 (one per product) = 7 queries instead of 1 —
     * the textbook N+1 problem. {@code JOIN FETCH} tells Hibernate to
     * retrieve the Order, its OrderLines, AND each line's Product in a
     * SINGLE SQL query with actual SQL JOINs, at the cost of a wider
     * result set (product columns repeated per line — fine at this scale,
     * a real concern only if Product had very large columns and an order
     * had very many lines).
     *
     * {@code distinct} matters here: a SQL JOIN across one Order to N
     * OrderLines returns N rows all describing the same Order — without
     * {@code distinct}, Hibernate would return the SAME Order object N
     * times in the result list (a classic "why do I have duplicate rows"
     * JOIN FETCH gotcha, distinct here operates at the JPQL/Hibernate
     * result-deduplication level, not just as a SQL DISTINCT clause).
     */
    @Query("select distinct o from Order o "
            + "left join fetch o.lines l "
            + "left join fetch l.product "
            + "where o.id = :id")
    Optional<Order> findByIdWithLines(@Param("id") Long id);

    /**
     * EXPLICIT JPQL + PAGEABLE — a join-based filter (search orders by
     * their customer's email) that a derived query method COULD express as
     * {@code findByCustomerEmailContainingIgnoreCase}, but is written as
     * {@code @Query} here to show the two styles side by side and because
     * real filter combinations quickly outgrow what's comfortably
     * expressible as a method name (imagine adding a status filter AND a
     * date range AND a minimum total to this same query — the method name
     * would become unreadable well before JPQL does).
     */
    @Query("select o from Order o where lower(o.customer.email) like lower(concat('%', :emailFragment, '%'))")
    Page<Order> searchByCustomerEmail(@Param("emailFragment") String emailFragment, Pageable pageable);
}
