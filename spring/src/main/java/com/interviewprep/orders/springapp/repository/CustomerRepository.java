package com.interviewprep.orders.springapp.repository;

import com.interviewprep.orders.springapp.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository. WHAT YOU GET FOR FREE by extending
 * {@code JpaRepository<Customer, Long>}: {@code save}, {@code findById},
 * {@code findAll}, {@code findAll(Pageable)}, {@code delete}, {@code count},
 * {@code existsById}, and batch variants — all implemented at runtime by a
 * dynamic proxy Spring generates (there is no hand-written implementation
 * class anywhere in this codebase; that's the point of Spring Data).
 *
 * {@code findByEmail} below is a DERIVED QUERY METHOD: Spring Data parses
 * the method name ("findBy" + "Email") at startup, matches "Email" against
 * the entity's {@code email} field, and generates
 * {@code SELECT * FROM customers WHERE email = ?} — no implementation code,
 * no annotation needed for this simple a case. This only works for
 * straightforward property-based queries; anything with joins, aggregation,
 * or complex conditions needs {@code @Query} (see {@code OrderRepository}
 * and {@code ProductRepository} for JPQL examples).
 */
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByEmail(String email);

    boolean existsByEmail(String email);
}
