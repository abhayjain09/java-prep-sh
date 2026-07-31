package com.interviewprep.orders.springapp.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating/updating a Customer.
 *
 * ============================================================================
 * WHY DTOs EXIST AT ALL — NEVER EXPOSE JPA ENTITIES DIRECTLY OVER REST
 * (this is the canonical explanation; every other DTO in this package
 * points back here instead of repeating it):
 * ============================================================================
 *
 * 1. LAZY-LOADING SERIALIZATION FAILURES: {@code Customer.orders} is
 *    {@code FetchType.LAZY}. If Jackson tried to serialize a {@code Customer}
 *    entity directly outside an open Hibernate session (recall
 *    {@code open-in-view: false} in application.yml — deliberately so),
 *    touching that lazy field throws {@code LazyInitializationException}.
 *    Even with open-in-view left on, serializing lazy associations
 *    transparently causes accidental N+1 queries at serialization time —
 *    invisible in the controller code, very visible in a slow endpoint.
 *
 * 2. TIGHT COUPLING OF API CONTRACT TO DB SCHEMA: if the entity is the
 *    response body, renaming a column, splitting a table, or changing a
 *    relationship (all internal refactors) becomes a BREAKING API CHANGE
 *    for every external client. A separate DTO is a stable contract you
 *    control independently of how the data happens to be stored today.
 *
 * 3. SECURITY — ACCIDENTALLY EXPOSING INTERNAL FIELDS: an entity can grow
 *    fields over time (an internal risk score, an audit flag, a soft-delete
 *    timestamp, eventually a password hash on some other entity) that
 *    should never leave the server. If the entity IS the response body,
 *    every new field is exposed by default unless someone remembers to
 *    annotate it {@code @JsonIgnore} — an easy step to forget. A DTO is
 *    opt-in by construction: a field is only exposed if someone deliberately
 *    added it to the DTO.
 *
 * A related, narrower point: request DTOs additionally prevent
 * "mass assignment" — if a client could POST a JSON body deserialized
 * directly into an entity, they could potentially set fields like
 * {@code id} or {@code version} that should only ever be server-controlled.
 * A request DTO simply has no such field to bind into.
 */
public record CustomerRequest(

        @NotBlank(message = "name must not be blank")
        @Size(max = 120, message = "name must be at most 120 characters")
        String name,

        @NotBlank(message = "email must not be blank")
        @Email(message = "email must be a well-formed email address")
        @Size(max = 254, message = "email must be at most 254 characters")
        String email
) {
    // WHY A RECORD IS FINE FOR A DTO (but not for the entity, see
    // entity/Customer.java's Javadoc): a request DTO is a genuine immutable
    // value with no identity and no persistence lifecycle — Jackson
    // deserializes JSON into it via its canonical constructor (Jackson
    // supports this for records since 2.12+ without extra annotations in a
    // Boot 3.x app), and it's discarded the moment the service layer has
    // read it. This is exactly the shape records are for.
}
