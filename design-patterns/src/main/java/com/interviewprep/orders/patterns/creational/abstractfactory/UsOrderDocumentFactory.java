package com.interviewprep.orders.patterns.creational.abstractfactory;

/** Concrete factory guaranteeing every product it creates is US-family. */
public class UsOrderDocumentFactory implements OrderDocumentFactory {
    @Override
    public Invoice createInvoice() {
        return new UsInvoice();
    }

    @Override
    public Receipt createReceipt() {
        return new UsReceipt();
    }
}
