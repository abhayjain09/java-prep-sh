package com.interviewprep.orders.patterns.creational.abstractfactory;

/** US invoices show sales tax broken out per state and no VAT number. */
public class UsInvoice implements Invoice {
    @Override
    public String render() {
        return "US Invoice [sales tax line, no VAT number, USD, Letter paper size]";
    }
}
