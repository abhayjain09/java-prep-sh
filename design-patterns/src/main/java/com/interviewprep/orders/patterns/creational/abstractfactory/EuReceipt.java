package com.interviewprep.orders.patterns.creational.abstractfactory;

public class EuReceipt implements Receipt {
    @Override
    public String render() {
        return "EU Receipt [EUR, A4 paper size, VAT-inclusive pricing shown]";
    }
}
