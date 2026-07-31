package com.interviewprep.orders.patterns.behavioral.chainofresponsibility;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.OrderLine;

import java.math.BigDecimal;
import java.util.List;

/** The data every validation handler in the chain needs access to. */
public record OrderValidationRequest(
        Customer customer,
        List<OrderLine> lines,
        Inventory inventory,
        BigDecimal customerCreditLimit,
        BigDecimal customerCurrentBalance,
        boolean flaggedForFraudReview) {
}
