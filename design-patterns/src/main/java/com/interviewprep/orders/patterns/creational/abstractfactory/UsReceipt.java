package com.interviewprep.orders.patterns.creational.abstractfactory;

public class UsReceipt implements Receipt {
    @Override
    public String render() {
        return "US Receipt [USD, Letter paper size, no VAT breakdown]";
    }
}
