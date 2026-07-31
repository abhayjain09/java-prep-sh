package com.interviewprep.orders;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.InsufficientStockException;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderLine;
import com.interviewprep.orders.domain.OrderStatus;
import com.interviewprep.orders.domain.Product;
import com.interviewprep.orders.service.OrderService;

import java.math.BigDecimal;
import java.util.List;

/**
 * Runnable walk-through of every Module 1 concept against the Order/Inventory
 * domain. Read alongside java-basics/EXPLANATION.md, which explains this
 * file line by line.
 *
 * Run with:
 *   cd java-basics/src/main/java
 *   javac com/interviewprep/orders/**\/*.java
 *   java com.interviewprep.orders.Main
 */
public class Main {

    public static void main(String[] args) {
        // --- Set up products, a customer, and inventory ---
        Product laptop = new Product("SKU-LAPTOP", "Laptop", new BigDecimal("1200.00"));
        Product mouse = new Product("SKU-MOUSE", "Wireless Mouse", new BigDecimal("25.00"));
        Customer customer = new Customer("CUST-1", "Ada Lovelace", "ada@example.com");

        Inventory inventory = new Inventory();
        inventory.restock(laptop.sku(), 5);
        inventory.restock(mouse.sku(), 10);

        OrderService orderService = new OrderService(inventory);

        // --- Place a valid order ---
        Order order1 = orderService.placeOrder(customer, List.of(
                new OrderLine(laptop, 1),
                new OrderLine(mouse, 2)
        ));
        System.out.println("Placed: " + order1);
        System.out.println("Stock remaining - laptop: " + inventory.stockOf(laptop.sku())
                + ", mouse: " + inventory.stockOf(mouse.sku()));

        // --- Attempt an order that exceeds available stock: demonstrates
        //     InsufficientStockException AND that placeOrder() rolls back
        //     cleanly (no partial reservation left behind). ---
        try {
            orderService.placeOrder(customer, List.of(
                    new OrderLine(laptop, 2),   // only 4 left — this line is fine
                    new OrderLine(mouse, 100)   // only 8 left — this line fails
            ));
        } catch (InsufficientStockException e) {
            System.out.println("Order rejected as expected: " + e.getMessage());
        }
        // Stock is unchanged from before the failed attempt — proves the rollback worked.
        System.out.println("Stock after failed order (should be unchanged) - laptop: "
                + inventory.stockOf(laptop.sku()) + ", mouse: " + inventory.stockOf(mouse.sku()));

        // --- Place a second valid order for status/reporting demos ---
        Order order2 = orderService.placeOrder(customer, List.of(new OrderLine(laptop, 1)));

        // --- OrderStatus transitions: legal path succeeds, illegal path is rejected ---
        order1.transitionTo(OrderStatus.CONFIRMED);
        order1.transitionTo(OrderStatus.SHIPPED);
        order1.transitionTo(OrderStatus.DELIVERED);
        System.out.println("order1 final status: " + order1.status());

        try {
            order1.transitionTo(OrderStatus.PENDING); // illegal: DELIVERED is terminal
        } catch (IllegalStateException e) {
            System.out.println("Illegal transition rejected as expected: " + e.getMessage());
        }

        // --- Imperative vs. Streams: same result, two implementations ---
        List<Order> allOrders = List.of(order1, order2);
        BigDecimal totalImperative = orderService.totalSpentByImperative(customer, allOrders);
        BigDecimal totalStreams = orderService.totalSpentByStreams(customer, allOrders);
        System.out.println("Total spent (imperative): " + totalImperative);
        System.out.println("Total spent (streams):    " + totalStreams);
        System.out.println("Both approaches agree: " + totalImperative.equals(totalStreams));

        // --- Collectors.groupingBy demo ---
        var byStatus = orderService.ordersByStatus(allOrders);
        System.out.println("Orders by status: " + byStatus);
    }
}
