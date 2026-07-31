package com.interviewprep.orders.patterns.behavioral.observer;

import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * CORRECT — the SUBJECT/PUBLISHER: maintains a list of
 * {@link OrderStatusListener}s and notifies ALL of them uniformly, without
 * knowing or caring what each one does (email vs. SMS vs. audit logging vs.
 * something added next quarter).
 *
 * {@code Order} itself (java-basics) is intentionally left UNCHANGED — this
 * publisher WRAPS the status-changing call, rather than baking observer
 * bookkeeping into the domain class itself. This keeps {@code Order} focused
 * purely on enforcing valid transitions (its one job, per Single
 * Responsibility — see SOLID.md), while this class owns the orthogonal
 * concern of "who needs to know when that happens."
 *
 * USAGE EXAMPLE:
 * <pre>{@code
 * OrderStatusPublisher publisher = new OrderStatusPublisher();
 * publisher.subscribe(new EmailNotificationListener());
 * publisher.subscribe(new SmsNotificationListener());
 * publisher.subscribe(new AuditLogListener());
 * // Adding a fourth listener later needs no change to this class or to
 * // the code below:
 * publisher.changeStatus(order, OrderStatus.SHIPPED);
 * }</pre>
 *
 * PRODUCTION NOTE: a real system usually swaps this in-process observer list
 * for an actual message broker/event bus (Kafka, SQS/SNS — see the aws/ and
 * system-design/ modules) once listeners need to run in separate services or
 * survive process restarts — but the CONCEPTUAL shape (publisher decoupled
 * from subscribers) is identical; Spring's own {@code ApplicationEventPublisher}
 * (Module 5) is this exact pattern, built into the framework.
 */
public class OrderStatusPublisher {

    private final List<OrderStatusListener> listeners = new ArrayList<>();

    public void subscribe(OrderStatusListener listener) {
        listeners.add(listener);
    }

    public void unsubscribe(OrderStatusListener listener) {
        listeners.remove(listener);
    }

    public void changeStatus(Order order, OrderStatus newStatus) {
        OrderStatus oldStatus = order.status();
        order.transitionTo(newStatus);
        for (OrderStatusListener listener : listeners) {
            listener.onStatusChanged(order, oldStatus, newStatus);
        }
    }
}
