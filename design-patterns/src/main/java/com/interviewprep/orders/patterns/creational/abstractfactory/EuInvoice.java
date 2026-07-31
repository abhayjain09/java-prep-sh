package com.interviewprep.orders.patterns.creational.abstractfactory;

/** EU invoices legally require a VAT number and VAT rate breakdown. */
public class EuInvoice implements Invoice {
    @Override
    public String render() {
        return "EU Invoice [VAT number required, VAT rate breakdown, EUR, A4 paper size]";
    }
}
