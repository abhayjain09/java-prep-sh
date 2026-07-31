package com.interviewprep.orders.springapp.service;

import com.interviewprep.orders.springapp.dto.OrderLineRequest;
import com.interviewprep.orders.springapp.dto.OrderRequest;
import com.interviewprep.orders.springapp.dto.OrderResponse;
import com.interviewprep.orders.springapp.entity.Customer;
import com.interviewprep.orders.springapp.entity.Order;
import com.interviewprep.orders.springapp.entity.OrderLine;
import com.interviewprep.orders.springapp.entity.OrderStatus;
import com.interviewprep.orders.springapp.exception.ResourceNotFoundException;
import com.interviewprep.orders.springapp.repository.CustomerRepository;
import com.interviewprep.orders.springapp.repository.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * The persistence-ready evolution of java-basics'
 * {@code service/OrderService.java} — same responsibility (tie Customer,
 * Product/Inventory, and Order together), radically simpler
 * {@code placeOrder} implementation because {@code @Transactional} now does
 * the work java-basics did by hand.
 *
 * ============================================================================
 * DIRECT CONTRAST WITH java-basics' {@code OrderService.placeOrder}
 * (read that method's Javadoc first if you haven't — this section assumes
 * you have):
 * ============================================================================
 * java-basics' version reserves stock for each line ONE AT A TIME against a
 * plain {@code HashMap}-backed {@code Inventory}, and if line 3 of 5 fails,
 * it must MANUALLY walk back through an {@code ArrayDeque} of already-
 * reserved lines and call {@code inventory.release(...)} on each — because
 * a plain in-memory {@code Map} has no concept of a transaction to roll
 * back; once {@code reserve()} returns, the mutation already happened, for
 * good, unless something undoes it by hand.
 *
 * THIS version's {@code placeOrder} below has NO such rollback code. It
 * just calls {@code productService.reserveStock(...)} in a loop and lets an
 * {@code InsufficientStockException} propagate straight out. WHY THAT'S
 * SAFE: {@code @Transactional} wraps this entire method in a single
 * database transaction. Spring's transaction interceptor (another AOP
 * proxy, same mechanism as {@code @Cacheable}) catches any
 * {@code RuntimeException} escaping the method and calls
 * {@code transaction.rollback()} on the underlying JDBC connection BEFORE
 * re-throwing — which undoes EVERY change made by EVERY line's
 * {@code reserveStock} call in this method, not just the one that failed,
 * with a single database-level ROLLBACK statement. No `Deque`, no manual
 * `release()` calls, no risk of the rollback logic itself having a bug.
 *
 * A DEEPER NUANCE WORTH KNOWING FOR A SENIOR INTERVIEW: with Hibernate's
 * default flush mode, entity field changes made via
 * {@code product.decrementStock(...)} inside this transaction are usually
 * NOT sent to the database as UPDATE statements immediately — they're
 * tracked in the persistence context and flushed together, typically right
 * before commit (or earlier if a query that could be affected by pending
 * changes runs first). That means in the COMMON case, if line 3 fails,
 * lines 1-2's stock decrements were likely never even sent to Postgres —
 * there's nothing to "undo" at the DB level because nothing was written
 * yet. But you should NOT rely on that as the safety mechanism: an early
 * flush (a native query, {@code flush()} called explicitly, or certain
 * batch-size/JDBC-driver behaviors) CAN send an UPDATE mid-transaction, and
 * {@code @Transactional}'s rollback is what guarantees correctness EITHER
 * way — it rolls back whatever was or wasn't flushed. Understanding this distinction
 * (persistence-context-level pending changes vs. actual DB writes vs. what
 * a transaction rollback guarantees regardless) is exactly the kind of
 * detail that separates "I used the annotation" from "I understand what
 * {@code @Transactional} actually does."
 *
 * WHY {@code @Transactional} DEFAULTS TO ROLLING BACK ONLY ON UNCHECKED
 * EXCEPTIONS: this is precisely why {@code InsufficientStockException}
 * extends {@code RuntimeException} rather than a checked {@code Exception}
 * (see that class's Javadoc) — Spring's default rollback rule is "roll back
 * on {@code RuntimeException} or {@code Error}, commit on a checked
 * exception" (a deliberate, if sometimes surprising, default inherited from
 * EJB conventions). Had this been a checked exception, {@code @Transactional}
 * would COMMIT the partial stock decrements by default unless
 * {@code rollbackFor = InsufficientStockException.class} were added
 * explicitly — a real, easy-to-miss production bug.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final ProductService productService;
    private final SecureRandom random = new SecureRandom();

    public OrderService(OrderRepository orderRepository, CustomerRepository customerRepository,
                         ProductService productService) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
        this.productService = productService;
    }

    /**
     * See the class Javadoc for the full contrast with java-basics'
     * hand-rolled rollback. {@code @Transactional} (no arguments) uses
     * Spring's defaults: {@code Propagation.REQUIRED} (join the caller's
     * transaction if one exists, otherwise start a new one — the right
     * default for almost every service method), read-write, default
     * isolation (whatever the DB's default is — {@code READ_COMMITTED} for
     * Postgres), and rollback on unchecked exceptions only (see above).
     */
    @Transactional
    public OrderResponse placeOrder(OrderRequest request) {
        Customer customer = customerRepository.findById(request.customerId())
                .orElseThrow(() -> ResourceNotFoundException.forId("Customer", request.customerId()));

        Order order = new Order(generateOrderNumber(), customer);

        for (OrderLineRequest lineRequest : request.lines()) {
            // reserveStock throws InsufficientStockException (unchecked) if
            // not enough stock — propagating it here aborts this loop AND
            // (via @Transactional) rolls back every prior iteration's
            // stock decrement in this same request, with no manual cleanup.
            var product = productService.reserveStock(lineRequest.productId(), lineRequest.quantity());
            OrderLine line = new OrderLine(product, lineRequest.quantity(), product.getPrice());
            order.addLine(line);
        }

        Order saved = orderRepository.save(order);
        return OrderResponse.from(saved);
    }

    /**
     * Uses the JOIN FETCH repository method (see
     * {@code OrderRepository.findByIdWithLines}) specifically so
     * {@code OrderResponse.from} can safely walk {@code order.getLines()}
     * and each line's {@code getProduct()} without triggering
     * {@code LazyInitializationException} (recall {@code open-in-view:
     * false}) or, if it didn't throw, an N+1 query storm.
     */
    @Transactional(readOnly = true)
    public OrderResponse getById(Long id) {
        Order order = orderRepository.findByIdWithLines(id)
                .orElseThrow(() -> ResourceNotFoundException.forId("Order", id));
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> listByStatus(OrderStatus status, Pageable pageable) {
        Page<Order> page = status != null
                ? orderRepository.findByStatus(status, pageable)
                : orderRepository.findAll(pageable);
        // NOTE: order.getLines() below is safe ONLY because we're still
        // inside the @Transactional method (Hibernate session open) even
        // though this repository call did NOT use JOIN FETCH — this list
        // endpoint deliberately accepts N+1-shaped lazy loading for a
        // PAGE of orders as a documented trade-off (see EXPLANATION.md's
        // "when NOT to JOIN FETCH" note: fetch-joining a paginated
        // one-to-many association is a known Hibernate pitfall — it
        // paginates the JOINED ROWS, not the parent entities, silently
        // returning fewer distinct orders per page than requested).
        return page.map(OrderResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> searchByCustomerEmail(String emailFragment, Pageable pageable) {
        return orderRepository.searchByCustomerEmail(emailFragment, pageable)
                .map(OrderResponse::from);
    }

    /**
     * Status transitions rely on {@code Order.transitionTo} (identical
     * delegation pattern to java-basics) for the legality check, and on
     * {@code @Version} (see {@code Order.version}'s Javadoc) for safety
     * under concurrent transition attempts on the SAME order — if two
     * requests both read this order at {@code version=3} and both try to
     * transition it, only the first to commit succeeds; the second's
     * UPDATE affects zero rows, Hibernate throws
     * {@code OptimisticLockException}, and {@code GlobalExceptionHandler}
     * turns that into a 409 the client can retry.
     */
    @Transactional
    public OrderResponse updateStatus(Long id, OrderStatus newStatus) {
        Order order = orderRepository.findByIdWithLines(id)
                .orElseThrow(() -> ResourceNotFoundException.forId("Order", id));
        order.transitionTo(newStatus);
        return OrderResponse.from(order);
    }

    /**
     * A short, readable, non-cryptographically-sensitive business
     * identifier — NOT a candidate for uniqueness enforcement via
     * generation alone (the {@code orders.order_number} unique constraint,
     * see {@code Order}'s {@code @Table}, is what actually guarantees
     * uniqueness; a collision here would surface as a constraint-violation
     * exception on save, vanishingly unlikely at this random-suffix width
     * but not mathematically impossible — worth knowing the DB constraint
     * is the real guarantee, this is just making collisions rare).
     */
    private String generateOrderNumber() {
        int suffix = random.nextInt(900_000) + 100_000;
        return "ORD-" + System.currentTimeMillis() + "-" + suffix;
    }
}
