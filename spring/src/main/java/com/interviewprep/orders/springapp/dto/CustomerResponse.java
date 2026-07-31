package com.interviewprep.orders.springapp.dto;

import com.interviewprep.orders.springapp.entity.Customer;
import org.springframework.hateoas.RepresentationModel;

/**
 * Response body for a Customer.
 *
 * WHY A CLASS EXTENDING {@code RepresentationModel<CustomerResponse>}, NOT A
 * RECORD (unlike {@code CustomerRequest}): this is the module's HATEOAS
 * example (see {@code CustomerController.getById} and
 * spring/README.md's "HATEOAS" section for when this is/isn't worth doing
 * in practice). {@code RepresentationModel} is a mutable base class Spring
 * HATEOAS uses to accumulate {@code Link}s onto a response object — a
 * record's all-final-fields, no-inheritance nature is incompatible with
 * extending any base class at all (records can only implement interfaces).
 * So the one response DTO demonstrating HATEOAS has to be a plain class;
 * every other DTO in this module that doesn't need links stays a record.
 */
public class CustomerResponse extends RepresentationModel<CustomerResponse> {

    private final Long id;
    private final String name;
    private final String email;

    public CustomerResponse(Long id, String name, String email) {
        this.id = id;
        this.name = name;
        this.email = email;
    }

    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(customer.getId(), customer.getName(), customer.getEmail());
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }
}
