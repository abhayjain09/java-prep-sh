package com.interviewprep.orders.patterns.creational.abstractfactory;

/** Concrete factory guaranteeing every product it creates is EU-family. */
public class EuOrderDocumentFactory implements OrderDocumentFactory {
    @Override
    public Invoice createInvoice() {
        return new EuInvoice();
    }

    @Override
    public Receipt createReceipt() {
        return new EuReceipt();
    }
}
