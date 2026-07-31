package com.interviewprep.orders.io;

/**
 * Tiny shared CSV-writing helper used by the exporters in this package.
 *
 * SCOPE NOTE: this only handles WRITING (escaping a field that might contain
 * a comma/quote/newline so the resulting row is still valid CSV). It
 * deliberately does NOT implement a full RFC 4180 reader (quoted-field
 * parsing, embedded commas, embedded newlines inside a quoted field). See
 * CsvOrderImporter's Javadoc for why the reader side stays naive here, and
 * EXERCISES.md for an exercise that closes this gap.
 *
 * In production, reach for a real CSV library (Apache Commons CSV, OpenCSV,
 * or Jackson's CSV module) rather than hand-rolling either direction — this
 * class exists purely to keep the exporters in this module dependency-free
 * (no Maven/Gradle is set up for java-advanced/file-io) while still writing
 * CSV that a spreadsheet program or another CSV reader will parse correctly.
 */
public final class CsvSupport {

    private CsvSupport() {
        // no instances — this is a pure static-utility holder
    }

    /**
     * Joins already-stringified field values into one properly-escaped CSV row
     * (no trailing newline — callers add that via their Writer's newline()).
     */
    public static String toCsvRow(String... fields) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                row.append(',');
            }
            row.append(escapeField(fields[i]));
        }
        return row.toString();
    }

    /**
     * RFC 4180-style escaping: a field is wrapped in double quotes if (and
     * only if) it contains a comma, a double quote, or a newline — and any
     * double quote inside it is doubled ("" is the CSV escape for a literal
     * quote character). Fields that need no special handling are left as-is,
     * both for readability of the output file and because unnecessary
     * quoting, while technically legal CSV, trips up some naive downstream
     * readers (a good argument for why writers should be conservative but
     * readers should be lenient).
     */
    public static String escapeField(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuoting = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        if (!needsQuoting) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
