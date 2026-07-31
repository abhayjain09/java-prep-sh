package com.interviewprep.orders.io;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/**
 * A deliberately minimal, hand-rolled JSON string builder.
 *
 * WHY THIS EXISTS: {@code java-advanced/file-io} has no build tool (no
 * Maven/Gradle) configured, so there is no Jackson/Gson on the classpath —
 * plain {@code javac} cannot pull in a third-party JSON library. This class
 * exists ONLY to demonstrate the *mechanics* underneath JSON serialization
 * (quoting, escaping control characters, assembling nested text with a
 * {@link StringBuilder}) using nothing but the JDK.
 *
 * DO NOT MODEL PRODUCTION CODE ON THIS CLASS. Module 5 (Spring Boot) wires up
 * real Jackson ({@code ObjectMapper}), which correctly and efficiently
 * handles: full Unicode escaping edge cases (including surrogate pairs),
 * custom (de)serializers, streaming for huge payloads without building one
 * giant String, date/time formats, polymorphic types, circular references,
 * and dozens of other cases this class does not even attempt. Hand-rolling
 * JSON in a real codebase is a common — and risky — anti-pattern: it looks
 * deceptively simple until a value contains a control character, an
 * unescaped quote, or a case nobody happened to test, and now there is a
 * subtly corrupt JSON file (or worse, a JSON injection vulnerability if the
 * value came from user input) in production. Use this class to understand
 * WHAT Jackson is doing for you, never as a substitute for it.
 */
public final class MiniJsonWriter {

    private MiniJsonWriter() {
        // no instances — pure static utility
    }

    /**
     * Escapes the characters JSON strings require special handling for:
     * backslash, double quote, and the common control characters, plus a
     * generic fallback ({@code \\uXXXX}) for any other character below
     * U+0020. This does NOT attempt full Unicode correctness (e.g. lone
     * surrogate handling) — another reason this is a teaching tool, not a
     * library replacement.
     */
    public static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    public static String quote(String value) {
        return "\"" + escape(value) + "\"";
    }

    /** A {@code "name":"value"} pair for a String value. */
    public static String field(String name, String stringValue) {
        return quote(name) + ":" + quote(stringValue);
    }

    /**
     * A {@code "name":value} pair for a numeric value. Numbers are written
     * UNQUOTED, exactly as JSON requires. {@code toPlainString()} (rather
     * than {@code BigDecimal.toString()}) avoids scientific notation
     * (e.g. {@code 1E+3}) that {@code BigDecimal} can otherwise produce for
     * some scales — technically legal JSON either way, but needlessly
     * surprising to a human (or a naive downstream parser) reading the file.
     */
    public static String field(String name, BigDecimal numericValue) {
        return quote(name) + ":" + numericValue.toPlainString();
    }

    /** A {@code "name":value} pair for an int value. */
    public static String field(String name, int numericValue) {
        return quote(name) + ":" + numericValue;
    }

    /**
     * Builds a JSON array by applying {@code toJsonObject} to each item and
     * joining the results with commas. This builds the ENTIRE array as one
     * in-memory String before anything is written to disk — fine for a
     * catalog of a few dozen/hundred products, but for a very large
     * collection a real streaming writer would emit {@code [}, each object,
     * and {@code ,}/{@code ]} directly to the output Writer as it goes,
     * never holding the whole document in memory at once (the same
     * streaming-vs-batch trade-off {@link CsvOrderImporter} discusses on the
     * read side, mirrored here on the write side).
     */
    public static <T> String arrayOf(List<T> items, Function<T, String> toJsonObject) {
        StringBuilder out = new StringBuilder();
        out.append('[');
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(toJsonObject.apply(items.get(i)));
        }
        out.append(']');
        return out.toString();
    }
}
