package com.interviewprep.orders.concurrency;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.InsufficientStockException;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderLine;
import com.interviewprep.orders.domain.OrderStatus;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Async version of {@code OrderService.placeOrder} (Module 1), composed as a
 * three-stage {@link CompletableFuture} pipeline: RESERVE STOCK -> CHARGE
 * PAYMENT -> CONFIRM ORDER, each stage running on the supplied {@link
 * Executor} rather than blocking the caller's thread.
 *
 * PIPELINE SHAPE (see README "CompletableFuture pipeline" section for the
 * full diagram):
 * <pre>
 *   supplyAsync(reserveAndCreateOrder)      // stage 1: CPU-ish, fast, in-memory
 *       .thenCompose(order ->
 *           paymentGateway.chargeAsync(order)   // stage 2: I/O-bound, slow
 *               .thenApply(payment -> confirm(order, payment))   // stage 3
 *               .exceptionally(ex -> cancelAfterPaymentFailure(order, ex)))
 *       .handle((outcome, ex) -> ex == null ? outcome : failedBeforePayment(ex));
 * </pre>
 *
 * WHY {@code thenCompose} AND NOT {@code thenApply} TO GO FROM STAGE 1 TO
 * STAGE 2: {@code chargeAsync(order)} itself returns a {@code
 * CompletableFuture<PaymentResult>}. Using {@code thenApply} here would
 * produce a {@code CompletableFuture<CompletableFuture<PaymentResult>>} — a
 * "future of a future," which is almost never what you want and forces an
 * extra unwrap later. {@code thenCompose} ("flatMap" for futures) is exactly
 * for chaining a step whose own result is itself an async computation,
 * collapsing the nesting automatically.
 *
 * WHY {@code exceptionally} FOR THE PAYMENT STEP BUT {@code handle} FOR THE
 * FINAL STEP: {@code exceptionally(Function<Throwable,T>)} only runs on
 * failure and can only see the exception — it's used here specifically to
 * produce a recovery VALUE (a cancelled {@link OrderOutcome}) for one
 * particular failure mode (payment declined after stock was already
 * reserved, which requires releasing that stock). {@code
 * handle(BiFunction<T,Throwable,R>)} ALWAYS runs, on both success and
 * failure, and sees both the value and the exception (one of the two is
 * always {@code null}) — used here as the pipeline's single terminal step
 * that normalizes EVERY remaining failure path (specifically, stock
 * reservation failing before payment was ever attempted) into the same
 * {@link OrderOutcome} type the success path produces.
 */
public class AsyncOrderProcessor {

    private final ReservableInventory inventory;
    private final PaymentGateway paymentGateway;
    private final Executor executor;
    // Thread-safe order id generation — see Module 1's OrderService, which
    // already previews AtomicLong for this exact reason: a plain
    // `long id++` is a read-modify-write race identical in shape to
    // Inventory.reserve()'s bug, and this class runs across a thread pool
    // where that would actually matter (Module 1's single-threaded Main
    // never exposed it).
    private final AtomicLong orderIdSequence = new AtomicLong(1);

    public AsyncOrderProcessor(ReservableInventory inventory, PaymentGateway paymentGateway, Executor executor) {
        this.inventory = inventory;
        this.paymentGateway = paymentGateway;
        this.executor = executor;
    }

    public CompletableFuture<OrderOutcome> processOrderAsync(Customer customer, List<OrderLine> requestedLines) {
        return CompletableFuture
                .supplyAsync(() -> reserveAndCreateOrder(customer, requestedLines), executor)
                .thenCompose(order -> paymentGateway.chargeAsync(order, executor)
                        .thenApply(payment -> confirm(order, payment))
                        .exceptionally(ex -> cancelAfterPaymentFailure(order, ex)))
                .handle((outcome, ex) -> ex == null ? outcome : failedBeforePayment(customer, ex));
    }

    /**
     * Mirrors {@code OrderService.placeOrder}'s all-or-nothing reservation
     * loop exactly (manual rollback via a Deque used as a stack) — the only
     * difference from Module 1 is that {@code inventory} here is a {@link
     * ReservableInventory} (thread-safe per-call), because this method now
     * runs concurrently across a thread pool for different orders, and
     * potentially concurrently with itself for the SAME sku across
     * different orders.
     */
    private Order reserveAndCreateOrder(Customer customer, List<OrderLine> requestedLines) {
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
            throw e; // supplyAsync wraps this in a CompletionException for downstream stages
        }

        Order order = new Order("ORD-" + orderIdSequence.getAndIncrement(), customer);
        requestedLines.forEach(order::addLine);
        return order;
    }

    private OrderOutcome confirm(Order order, PaymentResult payment) {
        order.transitionTo(OrderStatus.CONFIRMED);
        return new OrderOutcome(order, true,
                "confirmed — payment transaction " + payment.transactionId());
    }

    /** Payment failed AFTER stock was reserved: release it, then cancel the order. */
    private OrderOutcome cancelAfterPaymentFailure(Order order, Throwable ex) {
        for (OrderLine line : order.getLines()) {
            inventory.release(line.product().sku(), line.quantity());
        }
        order.transitionTo(OrderStatus.CANCELLED);
        return new OrderOutcome(order, false, "payment failed, stock released: " + rootCause(ex).getMessage());
    }

    /** Stock reservation itself failed: the order was never created, nothing to release. */
    private OrderOutcome failedBeforePayment(Customer customer, Throwable ex) {
        return new OrderOutcome(null, false,
                "order rejected for customer " + customer.id() + ": " + rootCause(ex).getMessage());
    }

    /**
     * Exceptions thrown inside {@code supplyAsync}/{@code thenApply}/etc.
     * arrive at downstream stages wrapped in a {@link CompletionException}
     * (or {@link ExecutionException} if observed via {@code Future.get()})
     * — unwrap to the original business exception so log messages and
     * {@code detail} strings show the real cause, not
     * "java.util.concurrent.CompletionException" with no useful message.
     */
    private static Throwable rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null
                && (cause instanceof CompletionException || cause instanceof ExecutionException)) {
            cause = cause.getCause();
        }
        return cause;
    }
}
