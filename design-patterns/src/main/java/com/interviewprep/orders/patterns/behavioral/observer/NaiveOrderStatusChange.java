package com.interviewprep.orders.patterns.behavioral.observer;

import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderStatus;

/**
 * WRONG — the code that changes an order's status directly and explicitly
 * calls every interested party (email, SMS, audit log) inline, by name.
 *
 * WHY THIS IS A PROBLEM:
 * 1. VIOLATES OPEN/CLOSED (see SOLID.md): adding a FOURTH interested party
 *    (say, a fraud-monitoring system that needs to know about every
 *    CANCELLED transition) means editing this method — code that ships
 *    order-status logic, arguably the most business-critical, most-tested
 *    path in the whole system — every time ANY new consumer of "order status
 *    changed" appears, forever.
 * 2. TIGHT COUPLING (see GRASP.md — Low Coupling): this class now directly
 *    depends on email-sending, SMS-sending, AND audit-logging concerns, when
 *    its actual job is just "move the order to a new status." A change to
 *    how SMS is sent (new provider, new API) forces a recompile/redeploy of
 *    status-changing code that has nothing conceptually to do with SMS.
 * 3. HARD TO TEST IN ISOLATION: unit-testing "does transitioning to SHIPPED
 *    work" now requires mocking or stubbing out email/SMS/audit
 *    infrastructure just to exercise the status-transition logic itself.
 *
 * See {@link OrderStatusPublisher}: listeners register themselves, the
 * publisher notifies whichever ones exist without knowing what any of them
 * do — new listeners are added with ZERO changes to the code that actually
 * changes order status.
 */
public class NaiveOrderStatusChange {

    private final EmailNotificationListener emailListener = new EmailNotificationListener();
    private final SmsNotificationListener smsListener = new SmsNotificationListener();
    private final AuditLogListener auditLogListener = new AuditLogListener();

    public void changeStatus(Order order, OrderStatus newStatus) {
        OrderStatus oldStatus = order.status();
        order.transitionTo(newStatus);

        // Hardcoded, by-name calls to every consumer this class happens to
        // know about today — a FOURTH consumer means editing this method.
        emailListener.onStatusChanged(order, oldStatus, newStatus);
        smsListener.onStatusChanged(order, oldStatus, newStatus);
        auditLogListener.onStatusChanged(order, oldStatus, newStatus);
    }
}
