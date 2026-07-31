package com.interviewprep.orders.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Demonstrates {@code java.io.Serializable} mechanics, and why this module's
 * actual import/export/zip pipeline (CsvOrderImporter, InventoryCsvExporter,
 * InventoryJsonExporter) uses text formats instead of Java serialization.
 *
 * NOTE: none of the real domain types (Customer, Product, OrderLine, Order)
 * implement {@code Serializable}, and this class deliberately does not
 * change that — {@link LegacyOrderSnapshot} below exists ONLY inside this
 * demo, as a throwaway type, to show the mechanics without touching the
 * shared domain model.
 */
public final class SerializationDemo {

    private SerializationDemo() {
        // no instances — pure static utility
    }

    /**
     * A minimal Serializable DTO, purely for this demo.
     *
     * WHY {@code serialVersionUID} MATTERS: it's an explicit version marker
     * for a class's serialized form. Omit it, and the JVM computes one from
     * the class's structure (fields, methods, interfaces...) at compile
     * time — meaning adding an unrelated method, or compiling with a
     * different {@code javac}, can silently change the computed UID and
     * make every previously-serialized instance unreadable
     * ({@code InvalidClassException}) even though none of the actual DATA
     * changed. Declaring it explicitly (as done below) is the bare minimum
     * discipline for using Serializable at all — and even then, adding,
     * removing, or retyping a FIELD still generally breaks backward
     * compatibility unless you hand-write {@code readObject}/
     * {@code writeObject} to bridge old and new forms, which most codebases
     * don't maintain correctly over years of changes.
     */
    static final class LegacyOrderSnapshot implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String orderId;
        private final String customerName;
        private final BigDecimal total;

        LegacyOrderSnapshot(String orderId, String customerName, BigDecimal total) {
            this.orderId = orderId;
            this.customerName = customerName;
            this.total = total;
        }

        @Override
        public String toString() {
            return "LegacyOrderSnapshot[orderId=%s, customerName=%s, total=%s]"
                    .formatted(orderId, customerName, total);
        }
    }

    /**
     * Round-trips a {@link LegacyOrderSnapshot} through
     * {@link ObjectOutputStream}/{@link ObjectInputStream}, then explains
     * (in comments, read alongside the printed output) why this module
     * prefers CSV/JSON for everything else it does.
     */
    public static void demonstrateRoundTrip() throws IOException, ClassNotFoundException {
        LegacyOrderSnapshot original = new LegacyOrderSnapshot("ORD-99", "Ada Lovelace", new BigDecimal("1250.00"));

        byte[] bytes;
        try (ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
             ObjectOutputStream objectOut = new ObjectOutputStream(byteOut)) {
            objectOut.writeObject(original);
            objectOut.flush(); // ensure everything is pushed into byteOut before we read it back below
            bytes = byteOut.toByteArray();
        }

        System.out.println("  serialized form: " + bytes.length
                + " bytes of a JVM-specific binary format (not human-readable, not cross-language)");

        Object restored;
        try (ObjectInputStream objectIn = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            restored = objectIn.readObject();
        }
        System.out.println("  restored: " + restored);

        // --- WHY THIS IS LARGELY DISCOURAGED IN MODERN CODE ---
        //
        // 1. SECURITY: calling ObjectInputStream.readObject() on bytes from
        //    an UNTRUSTED source (a network request body, an uploaded file,
        //    a message queue payload from outside your trust boundary) is a
        //    well-documented remote-code-execution vector — "Java
        //    deserialization gadget chains" were the root cause behind
        //    several real-world CVEs (roughly 2015-2019) in widely-used
        //    libraries. readObject() can be made to instantiate arbitrary
        //    Serializable classes already on the classpath and invoke
        //    methods on them purely as a SIDE EFFECT of deserializing —
        //    including classes never designed to be deserialized this way.
        //    If you must deserialize untrusted data with this API at all,
        //    use an allow-list filter (java.io.ObjectInputFilter, added in
        //    Java 9 and backported to 8u121+) — but text formats parsed by
        //    a data-only reader (no ability to instantiate arbitrary
        //    classes) sidestep this entire class of vulnerability.
        //
        // 2. VERSIONING: as the serialVersionUID Javadoc above explains,
        //    evolving a Serializable class safely over time requires real,
        //    sustained discipline. A CSV/JSON file, by contrast, is just
        //    text: a tolerant reader can ignore an unexpected extra column
        //    or field, and there's no hidden JVM-internal format version to
        //    fall out of sync with the code reading it.
        //
        // 3. INTEROP: the binary bytes produced above can only be read back
        //    by Java — and only a compatible JVM/classpath. The CSV/JSON
        //    this module actually exports can be opened in a spreadsheet,
        //    curl'd and parsed by a Python script, or consumed by a
        //    completely different service, which is precisely why
        //    InventoryCsvExporter/InventoryJsonExporter use text formats
        //    even though Serializable would have been fewer lines of code
        //    to write for this one demo.
    }
}
