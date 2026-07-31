package com.interviewprep.orders.patterns.structural.adapter;

import java.math.BigDecimal;

/**
 * The interface OUR checkout code is written against — clean, domain-shaped
 * method names and types (BigDecimal amount, a String confirmation id).
 * Every payment integration our system supports implements this, so checkout
 * code never needs to know which concrete gateway is behind it.
 */
public interface PaymentProcessor {
    String charge(BigDecimal amount);
}
