package com.interviewprep.orders.concurrency;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.OrderLine;

import java.util.List;

/**
 * A pending unit of work for the {@link ConcurrentCollectionsDemo}
 * producer/consumer pipeline — what a producer thread puts on the {@code
 * BlockingQueue} and a consumer thread takes off and hands to {@code
 * OrderService.placeOrder}. Deliberately a plain, unvalidated carrier (not a
 * domain type) since it represents "a request that arrived," which may
 * still turn out to be invalid or unsatisfiable once processed.
 */
public record OrderRequest(Customer customer, List<OrderLine> lines) {
}
