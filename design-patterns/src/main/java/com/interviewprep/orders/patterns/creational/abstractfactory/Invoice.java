package com.interviewprep.orders.patterns.creational.abstractfactory;

/** One "product" in the family: a tax-compliant invoice for a region. */
public interface Invoice {
    String render();
}
