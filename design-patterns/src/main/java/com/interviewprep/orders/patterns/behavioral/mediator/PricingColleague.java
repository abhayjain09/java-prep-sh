package com.interviewprep.orders.patterns.behavioral.mediator;

import java.math.BigDecimal;

/** Another COLLEAGUE — only knows the mediator, nothing about Inventory or Notification. */
public class PricingColleague {

    private final CheckoutMediator mediator;

    public PricingColleague(CheckoutMediator mediator) {
        this.mediator = mediator;
    }

    public void calculate(BigDecimal lineTotal) {
        BigDecimal withTax = lineTotal.add(lineTotal.multiply(new BigDecimal("0.08")));
        mediator.onPriceCalculated(withTax);
    }
}
