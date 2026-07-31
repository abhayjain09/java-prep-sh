package com.interviewprep.orders.io;

import com.interviewprep.orders.domain.Customer;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.OrderLine;
import com.interviewprep.orders.domain.Product;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bulk-imports orders from a CSV file into the exact domain objects Module 1
 * defined (Customer, Product, Order, OrderLine) — reusing them, not
 * redefining them.
 *
 * CSV SHAPE (one row per order LINE, not per order — an order with 3 line
 * items appears as 3 rows sharing the same orderId, which is how a real
 * order-export from an upstream system typically looks):
 *
 * <pre>
 * orderId,customerId,customerName,customerEmail,sku,productName,unitPrice,quantity
 * ORD-IMPORT-1,CUST-1,Ada Lovelace,ada@example.com,SKU-LAPTOP,Laptop,1200.00,1
 * ORD-IMPORT-1,CUST-1,Ada Lovelace,ada@example.com,SKU-MOUSE,Wireless Mouse,25.00,2
 * </pre>
 *
 * WHY STREAMING LINE-BY-LINE (the main teaching point of this class):
 * {@link #importOrders(Path)} below reads one line at a time via
 * {@link BufferedReader#readLine()} and turns it into domain objects
 * immediately, discarding the raw text line as soon as it's parsed. Memory
 * use is roughly O(1) per line read (plus whatever Orders/Products/Customers
 * accumulate — see the caveat below), NOT O(file size). Contrast this with
 * {@link #importOrders_NAIVE_LOADS_WHOLE_FILE(Path)}, which calls
 * {@code Files.readAllLines()} and materializes every line of the file as a
 * {@code List<String>} in memory BEFORE processing a single row.
 *
 * For a demo file of a few hundred bytes this makes zero observable
 * difference. For a real nightly batch file (a retailer reconciling a day's
 * orders from a legacy system might easily produce a multi-hundred-MB or
 * multi-GB CSV), the naive approach can push the JVM heap into
 * {@code OutOfMemoryError} territory before a single {@code Order} object
 * even exists — entirely avoidable, since nothing about CSV processing
 * requires the whole file to be resident at once.
 *
 * HONEST CAVEAT: this importer still accumulates every parsed {@code Order}
 * (and the small per-file {@code Customer}/{@code Product} lookup maps) in
 * memory for the life of the call, because it returns {@code List<Order>}.
 * For a file with millions of orders, returning everything as one List would
 * itself become the memory bottleneck. The fully-streaming production shape
 * would process (and discard) one Order at a time via a callback/consumer as
 * soon as all of its lines have been seen, instead of collecting a List — see
 * EXERCISES.md for that as a follow-on exercise. The line-by-line READING
 * shown here is still the essential, reusable technique either way.
 */
public final class CsvOrderImporter {

    private static final String HEADER =
            "orderId,customerId,customerName,customerEmail,sku,productName,unitPrice,quantity";
    private static final int EXPECTED_FIELDS = 8;

    private CsvOrderImporter() {
        // no instances — pure static utility
    }

    /**
     * CORRECT / production-shaped approach: streams the file one line at a
     * time via try-with-resources over a {@link BufferedReader}. The reader
     * (and the underlying file handle it wraps) is guaranteed to be closed
     * when this method returns, whether it returns normally or via an
     * exception thrown mid-file.
     */
    public static List<Order> importOrders(Path csvFile) throws IOException {
        Map<String, Order> ordersById = new LinkedHashMap<>(); // preserves first-seen order
        Map<String, Customer> customersById = new HashMap<>();
        Map<String, Product> productsBySku = new HashMap<>();

        long lineNumber = 0;
        long skipped = 0;
        boolean firstLine = true;

        try (BufferedReader reader = Files.newBufferedReader(csvFile)) {
            String line;
            // readLine() returns exactly one line, WITHOUT its line
            // terminator, and null at end-of-stream. Nothing about this call
            // depends on how large the file is — it reads only as far as the
            // next '\n'/'\r\n' and buffers a small chunk internally
            // (BufferedReader's default 8KB buffer), not the whole file.
            while ((line = reader.readLine()) != null) {
                lineNumber++;

                if (firstLine) {
                    firstLine = false;
                    if (line.strip().equalsIgnoreCase(HEADER)) {
                        continue; // skip the header row, don't count it as a skipped/bad row
                    }
                    // No header present — fall through and treat this first
                    // line as data. Real-world CSV exports are inconsistent
                    // about including a header; being tolerant here avoids
                    // silently dropping the first data row.
                }

                if (line.isBlank()) {
                    continue; // tolerate trailing/incidental blank lines
                }

                String[] fields = line.split(",", -1);
                if (fields.length != EXPECTED_FIELDS) {
                    System.err.println("Skipping malformed CSV line " + lineNumber + " (expected "
                            + EXPECTED_FIELDS + " fields, found " + fields.length + "): " + line);
                    skipped++;
                    continue;
                }

                try {
                    String orderId = fields[0].strip();
                    String customerId = fields[1].strip();
                    String customerName = fields[2].strip();
                    String customerEmail = fields[3].strip();
                    String sku = fields[4].strip();
                    String productName = fields[5].strip();
                    BigDecimal unitPrice = new BigDecimal(fields[6].strip());
                    int quantity = Integer.parseInt(fields[7].strip());

                    // computeIfAbsent means the SECOND row for the same
                    // customer/order/sku reuses the object already built from
                    // the first row instead of constructing (and validating)
                    // a duplicate — important since Customer/Product are
                    // records whose compact constructors run validation
                    // every time, and Order is a mutable identity object we
                    // want exactly one instance of per orderId.
                    Customer customer = customersById.computeIfAbsent(customerId,
                            id -> new Customer(id, customerName, customerEmail));
                    Product product = productsBySku.computeIfAbsent(sku,
                            s -> new Product(s, productName, unitPrice));
                    Order order = ordersById.computeIfAbsent(orderId, id -> new Order(id, customer));

                    order.addLine(new OrderLine(product, quantity));
                } catch (RuntimeException e) {
                    // NumberFormatException (bad price/quantity) or
                    // IllegalArgumentException (a domain record's own
                    // validation, e.g. a blank sku) land here. One bad row
                    // should not abort an entire batch import — log it and
                    // keep going, the same tolerant-reader posture real
                    // ETL/batch-import jobs take.
                    System.err.println("Skipping invalid CSV line " + lineNumber + ": " + line
                            + " (" + e.getMessage() + ")");
                    skipped++;
                }
            }
        }

        if (skipped > 0) {
            System.out.println("CSV import finished with " + skipped
                    + " skipped line(s) out of " + lineNumber + " read.");
        }

        return new ArrayList<>(ordersById.values());
    }

    /**
     * WRONG for large files — kept here, clearly labeled, ONLY to contrast
     * against {@link #importOrders(Path)} above. {@code Files.readAllLines()}
     * reads the ENTIRE file into a {@code List<String>} before this method
     * processes a single row. For a small demo file the result is identical;
     * for a genuinely large file this is the difference between a job that
     * scales and one that throws {@code OutOfMemoryError} in production at
     * 2am. Never call this in real code — it exists so you can see, side by
     * side, exactly which line is the problem.
     */
    public static List<Order> importOrders_NAIVE_LOADS_WHOLE_FILE(Path csvFile) throws IOException {
        List<String> allLines = Files.readAllLines(csvFile); // <-- the whole file, in memory, right here

        Map<String, Order> ordersById = new LinkedHashMap<>();
        Map<String, Customer> customersById = new HashMap<>();
        Map<String, Product> productsBySku = new HashMap<>();

        for (String line : allLines) {
            if (line.isBlank() || line.strip().equalsIgnoreCase(HEADER)) {
                continue;
            }
            String[] fields = line.split(",", -1);
            if (fields.length != EXPECTED_FIELDS) {
                continue;
            }
            try {
                String orderId = fields[0].strip();
                String customerId = fields[1].strip();
                String customerName = fields[2].strip();
                String customerEmail = fields[3].strip();
                String sku = fields[4].strip();
                String productName = fields[5].strip();
                BigDecimal unitPrice = new BigDecimal(fields[6].strip());
                int quantity = Integer.parseInt(fields[7].strip());

                Customer customer = customersById.computeIfAbsent(customerId,
                        id -> new Customer(id, customerName, customerEmail));
                Product product = productsBySku.computeIfAbsent(sku,
                        s -> new Product(s, productName, unitPrice));
                Order order = ordersById.computeIfAbsent(orderId, id -> new Order(id, customer));
                order.addLine(new OrderLine(product, quantity));
            } catch (RuntimeException e) {
                // Deliberately still logged, not swallowed silently — an
                // empty catch block hiding a parse failure would be its own,
                // separate anti-pattern (see INTERVIEW.md's Exception
                // Handling section in Module 1) on top of the memory problem
                // this method exists to demonstrate.
                System.err.println("Skipping invalid CSV line (naive importer): " + line + " (" + e.getMessage() + ")");
            }
        }
        return new ArrayList<>(ordersById.values());
    }
}
