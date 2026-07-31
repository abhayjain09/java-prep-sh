package com.interviewprep.orders.service;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.InsufficientStockException;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderLine;
import com.interviewprep.orders.domain.OrderStatus;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Application service that ties the domain objects together. This is where
 * Collections, Generics, Streams, and exception handling meet in one place —
 * everything Module 1 introduced, used together the way it would be in a
 * real (if framework-free) application layer.
 *
 * PRODUCTION NOTE: once Module 5 introduces Spring, this class becomes a
 * @Service, id generation moves to the database (identity column / sequence),
 * and placeOrder's manual rollback-on-failure logic below becomes a single
 * @Transactional annotation backed by real database transactions. Writing
 * the rollback by hand here first is deliberate — it shows *why*
 * transactions exist before Spring makes the problem invisible.
 */
public class OrderService {

    private final Inventory inventory;
    private final AtomicLong orderIdSequence = new AtomicLong(1);

    public OrderService(Inventory inventory) {
        this.inventory = inventory;
    }

    /**
     * Reserves stock for every line and creates the Order, or reserves
     * nothing at all if any line can't be satisfied.
     *
     * WHY MANUAL ROLLBACK: reserve() mutates Inventory immediately for each
     * line. If line 3 of 5 fails with InsufficientStockException, lines 1
     * and 2 have already decremented real stock — left alone, that stock
     * would be silently lost (reserved for an order that was never placed).
     * The catch block below releases everything reserved so far before
     * re-throwing, so a failed placeOrder() call has NO net effect on
     * inventory — exactly the "all or nothing" guarantee a database
     * transaction gives you for free, done by hand here to show the problem
     * a transaction solves.
     */
    public Order placeOrder(Customer customer, List<OrderLine> requestedLines) {
        Deque<OrderLine> reserved = new ArrayDeque<>();
        try {
            for (OrderLine line : requestedLines) {
                inventory.reserve(line.product().sku(), line.quantity());
                reserved.push(line);
            }
        } catch (InsufficientStockException e) {
            for (OrderLine line : reserved) {
                inventory.release(line.product().sku(), line.quantity());
            }
            throw e; // preserve the original exception and stack trace — never swallow it
        }

        Order order = new Order("ORD-" + orderIdSequence.getAndIncrement(), customer);
        requestedLines.forEach(order::addLine);
        return order;
    }

    /**
     * IMPERATIVE version — computes total spend across a customer's orders
     * with an explicit loop and accumulator. Kept side-by-side with
     * totalSpentByStreams() below so the two styles can be compared
     * directly; see java-basics/README.md section 4 for the discussion.
     */
    public BigDecimal totalSpentByImperative(Customer customer, List<Order> orders) {
        BigDecimal total = BigDecimal.ZERO;
        for (Order order : orders) {
            if (order.customer().equals(customer)) {
                total = total.add(order.totalAmount());
            }
        }
        return total;
    }

    /** STREAMS version of the same computation — filter, map, reduce. */
    public BigDecimal totalSpentByStreams(Customer customer, List<Order> orders) {
        return orders.stream()
                .filter(order -> order.customer().equals(customer))
                .map(Order::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Groups orders by status using Collectors.groupingBy — the Streams
     * equivalent of building a Map<OrderStatus, List<Order>> by hand with a
     * loop and computeIfAbsent(). This is one of the most common real-world
     * Streams idioms (dashboards, reporting, "orders needing attention").
     */
    public Map<OrderStatus, List<Order>> ordersByStatus(List<Order> orders) {
        return orders.stream()
                .collect(Collectors.groupingBy(Order::status));
    }
}
