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
 * Exports the same inventory snapshot as {@link InventoryCsvExporter}, but as
 * a JSON array — using the hand-rolled {@link MiniJsonWriter} rather than a
 * real JSON library (none is available without a build tool in this module;
 * see {@code MiniJsonWriter}'s Javadoc and this module's README for why that
 * is a deliberate, narrow teaching choice and not a recommendation).
 *
 * PRODUCTION NOTE: in Module 5, this becomes a one-liner —
 * {@code objectMapper.writeValue(outputFile, catalogSnapshotDtos)} — because
 * Jackson is on the classpath and handles everything this class does by hand
 * (and much more) correctly and efficiently.
 */
public final class InventoryJsonExporter {

    private InventoryJsonExporter() {
        // no instances — pure static utility
    }

    public static void export(List<Product> catalog, Inventory inventory, Path outputFile) throws IOException {
        String json = MiniJsonWriter.arrayOf(catalog, product -> {
            int quantityOnHand = inventory.stockOf(product.sku());
            return "{"
                    + MiniJsonWriter.field("sku", product.sku()) + ","
                    + MiniJsonWriter.field("name", product.name()) + ","
                    + MiniJsonWriter.field("unitPrice", product.price()) + ","
                    + MiniJsonWriter.field("quantityOnHand", quantityOnHand)
                    + "}";
        });

        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            writer.write(json);
            writer.newLine();
        }
    }
}
