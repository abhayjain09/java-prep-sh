package com.interviewprep.orders.concurrency;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.OrderLine;
import com.interviewprep.orders.domain.Product;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Processes a BATCH of orders concurrently: a fixed thread pool ({@link
 * Executors#newFixedThreadPool(int)}) backs the {@link AsyncOrderProcessor}
 * pipeline, and {@link CompletableFuture#allOf} waits for the whole batch to
 * finish before reporting results. Deliberately seeds a mix of outcomes —
 * some orders succeed, one runs out of stock, one gets its payment declined
 * — so every branch of {@link AsyncOrderProcessor}'s pipeline actually
 * executes in one run.
 *
 * Run standalone with:
 *   java -cp out com.interviewprep.orders.concurrency.BatchOrderProcessingDemo
 */
public final class BatchOrderProcessingDemo {

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        System.out.println("=== Batch order processing: ExecutorService + CompletableFuture pipeline ===");

        Product laptop = new Product("SKU-LAPTOP", "Laptop", new BigDecimal("1200.00"));
        Product mouse = new Product("SKU-MOUSE", "Wireless Mouse", new BigDecimal("25.00"));
        // Priced above PaymentGateway's DECLINE_THRESHOLD (5000.00) so this
        // order's payment step deterministically fails, exercising
        // cancelAfterPaymentFailure()'s stock-release path.
        Product server = new Product("SKU-SERVER", "Rack Server", new BigDecimal("6000.00"));

        ConcurrentInventory inventory = new ConcurrentInventory();
        // Deliberately scarce laptop stock: 3 units, but 4 customers order
        // one each below, so the 4th reservation fails with
        // InsufficientStockException — exercising failedBeforePayment()'s
        // "rejected before payment was ever attempted" path.
        inventory.restock(laptop.sku(), 3);
        inventory.restock(mouse.sku(), 50);
        inventory.restock(server.sku(), 10);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            PaymentGateway paymentGateway = new PaymentGateway();
            AsyncOrderProcessor processor = new AsyncOrderProcessor(inventory, paymentGateway, executor);

            List<CompletableFuture<OrderOutcome>> futures = new ArrayList<>();
            futures.add(submitOrder(processor, "CUST-1", laptop, 1)); // succeeds
            futures.add(submitOrder(processor, "CUST-2", laptop, 1)); // succeeds
            futures.add(submitOrder(processor, "CUST-3", laptop, 1)); // succeeds, stock now exactly exhausted
            futures.add(submitOrder(processor, "CUST-4", laptop, 1)); // fails: InsufficientStockException
            futures.add(submitOrder(processor, "CUST-5", mouse, 2));  // succeeds
            futures.add(submitOrder(processor, "CUST-6", server, 1)); // reserves fine, then payment declines

            // allOf's returned future completes once EVERY future in the
            // array completes (successfully OR exceptionally) — but note it
            // does NOT itself carry any of their results; join() on allOf
            // just gives us a synchronization point to wait past before
            // reading each individual future's already-resolved outcome
            // below via join() (safe: allOf already guaranteed completion).
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            System.out.println();
            System.out.println("--- Batch results (order of completion may vary run to run) ---");
            for (CompletableFuture<OrderOutcome> future : futures) {
                OrderOutcome outcome = future.join();
                String orderId = outcome.order() != null ? outcome.order().id() : "(none)";
                System.out.println((outcome.success() ? "SUCCESS " : "FAILED  ")
                        + "order=" + orderId + " -> " + outcome.detail());
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private static CompletableFuture<OrderOutcome> submitOrder(AsyncOrderProcessor processor, String customerId,
                                                                Product product, int quantity) {
        Customer customer = new Customer(customerId, customerId + " Customer", customerId.toLowerCase() + "@example.com");
        return processor.processOrderAsync(customer, List.of(new OrderLine(product, quantity)));
    }
}
