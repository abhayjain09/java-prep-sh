package com.interviewprep.orders.concurrency;

import java.math.BigDecimal;

/**
 * Result of a successful (approved) payment charge — see {@link
 * PaymentGateway}. A stub value object for this module; a real payment
 * integration (Module 6/7 territory: Spring, external APIs) would have a
 * far richer result shape (processor reference, card network response
 * codes, etc.).
 */
public record PaymentResult(String transactionId, BigDecimal amountCharged) {
}
