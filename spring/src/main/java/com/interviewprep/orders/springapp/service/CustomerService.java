package com.interviewprep.orders.springapp.service;

import com.interviewprep.orders.springapp.dto.CustomerRequest;
import com.interviewprep.orders.springapp.entity.Customer;
import com.interviewprep.orders.springapp.exception.ResourceNotFoundException;
import com.interviewprep.orders.springapp.repository.CustomerRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deliberately simple compared to {@code OrderService}/{@code ProductService}
 * — plain CRUD with no caching or complex state machine, included mainly so
 * {@code CustomerController} has a real service to demonstrate the HATEOAS
 * example against. Not every service in a real application needs to be
 * complicated; resisting the urge to add speculative behavior (caching,
 * events) to a class that doesn't need it yet is itself a design decision
 * worth being able to defend ("YAGNI" — You Aren't Gonna Need It).
 */
@Service
public class CustomerService {

    private final CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Transactional
    public Customer create(CustomerRequest request) {
        if (customerRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("A customer with email " + request.email() + " already exists");
        }
        return customerRepository.save(new Customer(request.name(), request.email()));
    }

    @Transactional(readOnly = true)
    public Customer getById(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.forId("Customer", id));
    }

    @Transactional(readOnly = true)
    public Page<Customer> list(Pageable pageable) {
        return customerRepository.findAll(pageable);
    }

    @Transactional
    public Customer update(Long id, CustomerRequest request) {
        Customer customer = getById(id);
        customer.setName(request.name());
        customer.setEmail(request.email());
        return customer; // managed entity — dirty checking flushes the update, see ProductService's note
    }

    @Transactional
    public void delete(Long id) {
        if (!customerRepository.existsById(id)) {
            throw ResourceNotFoundException.forId("Customer", id);
        }
        customerRepository.deleteById(id);
    }
}
