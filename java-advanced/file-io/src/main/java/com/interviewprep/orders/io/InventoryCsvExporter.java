package com.interviewprep.orders.io;

import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.Product;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Exports a point-in-time inventory snapshot to CSV.
 *
 * WHY THIS TAKES A {@code List<Product>} CATALOG PARAMETER instead of asking
 * {@code Inventory} for "all known SKUs": {@link Inventory} (Module 1) is
 * deliberately encapsulated — it exposes only {@code stockOf(sku)},
 * {@code reserve}, {@code release}, {@code restock}, and intentionally has
 * no method to enumerate every SKU it has ever seen (see its Javadoc: it's
 * kept decoupled from the rest of the domain on purpose). Rather than adding
 * an enumeration method to Module 1's Inventory just to support exporting
 * (widening that class's API for a concern that belongs to this module), the
 * exporter takes the catalog to report on as an explicit parameter and only
 * ever calls the one read-only method {@code Inventory} already exposes.
 * This is the same "ask for what you need, don't ask an object to expose its
 * internals" principle Module 1's README calls out for encapsulation.
 */
public final class InventoryCsvExporter {

    private static final String HEADER = "sku,name,unitPrice,quantityOnHand";

    private InventoryCsvExporter() {
        // no instances — pure static utility
    }

    /**
     * Writes one CSV row per catalog product. Uses
     * {@link Files#newBufferedWriter(Path, java.nio.charset.Charset)} inside
     * try-with-resources: the writer (and its underlying file channel) is
     * guaranteed to be flushed and closed even if a write throws partway
     * through, which matters here because a half-written CSV file left open
     * (or, worse, left un-flushed in a buffer that never gets written to
     * disk) is worse than no file at all — a downstream reader can't tell a
     * truncated file from a complete one just by looking at it.
     */
    public static void export(List<Product> catalog, Inventory inventory, Path outputFile) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            writer.write(HEADER);
            writer.newLine();
            for (Product product : catalog) {
                int quantityOnHand = inventory.stockOf(product.sku());
                writer.write(CsvSupport.toCsvRow(
                        product.sku(),
                        product.name(),
                        product.price().toPlainString(),
                        Integer.toString(quantityOnHand)));
                writer.newLine();
            }
        }
    }
}
