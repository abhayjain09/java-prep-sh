package com.interviewprep.orders.saga.orderplacement;

import java.math.BigDecimal;

/**
 * ILLUSTRATIVE — see ../SagaContext.java's header note on this LLD's scope
 * and conventions.
 *
 * The data shape carried across the network between saga steps for one
 * order line. Deliberately NOT java-basics' OrderLine record reused
 * as-is: once Order and Inventory are separate services, each owns its
 * own model, and this DTO is the explicit "public contract" between them
 * — see README.md Section 10's DDD discussion of Customer/Supplier
 * relationships and why a shared kernel is used sparingly and
 * deliberately (CustomerId), not by default for every type crossing a
 * bounded-context boundary.
 */
public record OrderLineDto(String sku, int quantity, BigDecimal unitPrice) {
}
