package com.interviewprep.orders.domain;

/**
 * A customer placing orders.
 *
 * WHY A RECORD (Java 16+): Customer has no behavior of its own, no mutable
 * state, and no identity beyond its fields — exactly the shape a record is
 * for. The compiler generates the canonical constructor, accessors
 * (id(), name(), email() — note: no "get" prefix, unlike a JavaBean),
 * equals()/hashCode()/toString() from the field list. Pre-Java 16 this
 * would have been ~25 lines of boilerplate (or a Lombok @Value class).
 *
 * PRODUCTION NOTE: this is a plain domain object, not a JPA entity. When
 * Module 5 (Spring Data JPA) introduces persistence, the entity will be a
 * separate mutable class — JPA entities need a no-arg constructor and
 * mutable fields for the persistence provider to populate, which conflicts
 * with a record's all-args-only canonical constructor and immutability.
 */
public record Customer(String id, String name, String email) {

    // Compact canonical constructor: validates before the implicit field
    // assignment happens. This runs for every construction path, including
    // records' normal (String, String, String) constructor.
    public Customer {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Customer id must not be blank");
        }
        if (email == null || !email.contains("@")) {
            throw new IllegalArgumentException("Customer email is invalid: " + email);
        }
    }
}
