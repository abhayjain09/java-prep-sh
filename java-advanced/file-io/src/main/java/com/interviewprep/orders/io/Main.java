package com.interviewprep.orders.io;

import com.interviewprep.orders.domain.Inventory;
import com.interviewprep.orders.domain.Order;
import com.interviewprep.orders.domain.Product;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Runnable walk-through of every Module 2 (File System APIs) concept,
 * applied to the same Order/Inventory domain Module 1 introduced. Read
 * alongside java-advanced/file-io/EXPLANATION.md, which explains this file
 * section by section.
 *
 * Build/run instructions (both source roots compiled together, since this
 * module reuses Module 1's domain classes rather than redefining them) are
 * in java-advanced/file-io/README.md.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        // Everything this demo writes goes under one throwaway OS temp
        // directory, so re-running the demo never collides with a previous
        // run and nothing is left behind inside the repo itself.
        Path workspace = Files.createTempDirectory("orders-file-io-demo");
        Path incomingDir = workspace.resolve("incoming");
        Path exportsDir = workspace.resolve("exports");
        Files.createDirectories(incomingDir);
        Files.createDirectories(exportsDir);
        System.out.println("Workspace for this run: " + workspace);
        System.out.println("(Every file below is real — open it in a text editor after this program exits.)");
        System.out.println();

        // --- Product catalog + starting stock, same shape as Module 1's Main ---
        Product laptop = new Product("SKU-LAPTOP", "Laptop", new BigDecimal("1200.00"));
        Product mouse = new Product("SKU-MOUSE", "Wireless Mouse", new BigDecimal("25.00"));
        Product keyboard = new Product("SKU-KEYBOARD", "Mechanical Keyboard", new BigDecimal("85.00"));
        List<Product> catalog = List.of(laptop, mouse, keyboard);

        Inventory inventory = new Inventory();
        inventory.restock(laptop.sku(), 5);
        inventory.restock(mouse.sku(), 20);
        inventory.restock(keyboard.sku(), 12);

        // ================================================================
        // 1. Bulk CSV import — streaming, line-by-line
        // ================================================================
        System.out.println("=== 1. CSV bulk import (streaming line-by-line) ===");
        Path batchFile = incomingDir.resolve("orders-batch-1.csv");
        Files.writeString(batchFile, sampleOrderCsv(), StandardCharsets.UTF_8);

        List<Order> imported = CsvOrderImporter.importOrders(batchFile);
        System.out.println("Imported " + imported.size() + " order(s):");
        imported.forEach(order -> System.out.println("  " + order));

        // Same file, naive whole-file-in-memory approach — identical RESULT
        // for this tiny demo file. See CsvOrderImporter's Javadoc for why
        // this doesn't scale to a real multi-GB nightly batch file.
        List<Order> importedNaively = CsvOrderImporter.importOrders_NAIVE_LOADS_WHOLE_FILE(batchFile);
        System.out.println("Naive (whole-file-in-memory) import agrees on order count: "
                + (importedNaively.size() == imported.size()));
        System.out.println();

        // ================================================================
        // 2. Export inventory snapshot — CSV and hand-rolled JSON
        // ================================================================
        System.out.println("=== 2. Inventory export (CSV + hand-rolled JSON) ===");
        Path csvExport = exportsDir.resolve("inventory-snapshot.csv");
        Path jsonExport = exportsDir.resolve("inventory-snapshot.json");
        InventoryCsvExporter.export(catalog, inventory, csvExport);
        InventoryJsonExporter.export(catalog, inventory, jsonExport);
        System.out.println("Wrote " + csvExport);
        System.out.println("Wrote " + jsonExport);
        System.out.println("--- CSV contents ---");
        System.out.println(Files.readString(csvExport));
        System.out.println("--- JSON contents ---");
        System.out.println(Files.readString(jsonExport));

        // ================================================================
        // 3. Zip the exported files
        // ================================================================
        System.out.println("=== 3. Zipping exports (java.util.zip) ===");
        Path zipFile = exportsDir.resolve("exports-bundle.zip");
        ExportZipper.zip(List.of(csvExport, jsonExport), zipFile);
        System.out.println("Wrote " + zipFile + " containing: " + ExportZipper.listEntries(zipFile));
        System.out.println();

        // ================================================================
        // 4. try-with-resources: correct vs. the error-prone predecessor
        // ================================================================
        System.out.println("=== 4. Resource handling: manual close() vs. try-with-resources ===");
        System.out.println("-- WRONG (manual close-in-finally) --");
        ResourceHandlingDemo.manualCloseInFinally_WRONG();
        System.out.println("-- CORRECT (try-with-resources; close() exceptions become suppressed, not lost) --");
        ResourceHandlingDemo.tryWithResources_CORRECT();
        System.out.println();

        // ================================================================
        // 5. FileChannel.lock() protecting a single-writer export
        // ================================================================
        System.out.println("=== 5. File locking during export (FileChannel.lock()) ===");
        Path lockedExport = exportsDir.resolve("locked-export.txt");
        ExportFileLocker.exportWithLock(lockedExport, "Exclusive export protected by FileChannel.lock()\n");
        System.out.println("Wrote (under lock): " + lockedExport);
        System.out.println("Contents: " + Files.readString(lockedExport).strip());
        System.out.println();

        // ================================================================
        // 6. WatchService — pick up a file dropped into incomingDir at runtime
        // ================================================================
        System.out.println("=== 6. WatchService: watching " + incomingDir + " ===");
        System.out.println("(orders-batch-1.csv above doesn't trigger an event here — it existed BEFORE");
        System.out.println(" the watch was registered. Only files created AFTER registration fire events.)");

        Thread dropFileLater = new Thread(() -> {
            try {
                Thread.sleep(500);
                Path droppedFile = incomingDir.resolve("watched-drop.csv");
                Files.writeString(droppedFile, sampleWatchedCsv(), StandardCharsets.UTF_8);
                System.out.println("  [background thread] dropped " + droppedFile.getFileName() + " into the watched directory");
            } catch (IOException e) {
                System.err.println("  [background thread] failed to drop watched file: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "file-dropper");
        dropFileLater.start();

        OrderImportWatcher.watchOnce(incomingDir, Duration.ofSeconds(5), autoImported ->
                System.out.println("  auto-imported " + autoImported.size() + " order(s) via WatchService: " + autoImported));

        dropFileLater.join();
        System.out.println();

        // ================================================================
        // 7. Serializable vs. text formats
        // ================================================================
        System.out.println("=== 7. java.io.Serializable: mechanics, and why this module avoids it ===");
        SerializationDemo.demonstrateRoundTrip();

        System.out.println();
        System.out.println("Done. Inspect every generated file under: " + workspace);
    }

    private static String sampleOrderCsv() {
        return """
                orderId,customerId,customerName,customerEmail,sku,productName,unitPrice,quantity
                ORD-IMPORT-1,CUST-1,Ada Lovelace,ada@example.com,SKU-LAPTOP,Laptop,1200.00,1
                ORD-IMPORT-1,CUST-1,Ada Lovelace,ada@example.com,SKU-MOUSE,Wireless Mouse,25.00,2
                ORD-IMPORT-2,CUST-2,Grace Hopper,grace@example.com,SKU-KEYBOARD,Mechanical Keyboard,85.00,1
                ORD-IMPORT-2,CUST-2,Grace Hopper,grace@example.com,SKU-MOUSE,Wireless Mouse,25.00,1
                this-line-is-malformed-and-will-be-skipped
                ORD-IMPORT-3,CUST-1,Ada Lovelace,ada@example.com,SKU-LAPTOP,Laptop,1200.00,2
                """;
    }

    private static String sampleWatchedCsv() {
        return """
                orderId,customerId,customerName,customerEmail,sku,productName,unitPrice,quantity
                ORD-WATCHED-1,CUST-3,Margaret Hamilton,margaret@example.com,SKU-MOUSE,Wireless Mouse,25.00,3
                """;
    }
}
