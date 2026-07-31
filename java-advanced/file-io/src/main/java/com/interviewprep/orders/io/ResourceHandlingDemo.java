package com.interviewprep.orders.io;

import java.io.Closeable;
import java.io.IOException;

/**
 * Demonstrates try-with-resources (Java 7+) versus its manual,
 * error-prone predecessor — using a fake resource ({@link FlakyResource})
 * whose {@code close()} can itself fail, which is exactly the case that
 * makes correct manual cleanup hard. Real examples where close() can throw:
 * a buffered {@code FileOutputStream} flushing on close, a database
 * connection whose close() fails because the network dropped, a
 * {@code Socket} whose close() fails mid-TLS-shutdown.
 */
public final class ResourceHandlingDemo {

    private ResourceHandlingDemo() {
        // no instances — pure static utility
    }

    /** A tiny stand-in for any real closeable resource, configurable to fail on use and/or on close. */
    static final class FlakyResource implements Closeable {
        private final String name;
        private final boolean failOnUse;
        private final boolean failOnClose;

        FlakyResource(String name, boolean failOnUse, boolean failOnClose) {
            this.name = name;
            this.failOnUse = failOnUse;
            this.failOnClose = failOnClose;
        }

        void use() throws IOException {
            System.out.println("  using " + name);
            if (failOnUse) {
                throw new IOException(name + " failed during use()");
            }
        }

        @Override
        public void close() throws IOException {
            System.out.println("  closing " + name);
            if (failOnClose) {
                throw new IOException(name + " failed during close()");
            }
        }
    }

    /**
     * WRONG / error-prone predecessor to try-with-resources (pre-Java 7
     * style, still seen in legacy codebases). Problems this demonstrates:
     * <ol>
     *   <li>Verbose: resources must be declared outside the try block and
     *       null-checked in {@code finally}, in case the resource's own
     *       constructor/open call throws partway through acquiring it.</li>
     *   <li><b>If {@code resourceA.close()} throws, {@code resourceB.close()}
     *       in the same {@code finally} block is NEVER REACHED</b> —
     *       resourceB leaks for the rest of the JVM's life. Fixing this
     *       "properly" by hand requires a nested try/finally PER resource,
     *       which is exactly the boilerplate try-with-resources exists to
     *       eliminate.</li>
     *   <li>If both the try body and a close() call throw, this code lets
     *       the close() exception in {@code finally} replace the original
     *       exception from the try body — the real root cause of the
     *       failure is silently lost.</li>
     * </ol>
     */
    public static void manualCloseInFinally_WRONG() {
        FlakyResource resourceA = null;
        FlakyResource resourceB = null;
        try {
            resourceA = new FlakyResource("A", false, true);  // will fail on close()
            resourceB = new FlakyResource("B", false, false); // closes cleanly, if we ever get there
            resourceA.use();
            resourceB.use();
            throw new IllegalStateException("business logic failure inside the try body");
        } catch (IOException | IllegalStateException e) {
            System.out.println("  caught: " + e.getMessage());
        } finally {
            // BUG: if resourceA.close() throws, control jumps straight to
            // the catch below and resourceB.close() is never called.
            try {
                if (resourceA != null) {
                    resourceA.close();
                }
                if (resourceB != null) {
                    resourceB.close();
                }
            } catch (IOException e) {
                System.out.println("  exception during manual close (any earlier business exception is now LOST): "
                        + e.getMessage());
            }
        }
    }

    /**
     * CORRECT: try-with-resources. Resources declared in the {@code try(...)}
     * header are guaranteed to have {@code close()} called on EVERY one of
     * them, in reverse declaration order, no matter how the try body exits
     * (normally, by exception, or by return). If the try body throws AND one
     * or more close() calls also throw, the body's exception is the one that
     * propagates (it's the real root cause) and every close() exception is
     * attached to it as a <b>suppressed exception</b>
     * ({@link Throwable#getSuppressed()}) instead of being lost — directly
     * fixing the data-loss bug demonstrated as WRONG above.
     */
    public static void tryWithResources_CORRECT() {
        try (FlakyResource resourceA = new FlakyResource("A", false, true);
             FlakyResource resourceB = new FlakyResource("B", false, true)) {
            resourceA.use();
            resourceB.use();
            throw new IllegalStateException("business logic failure inside the try body");
        } catch (IOException | IllegalStateException e) {
            System.out.println("  caught primary exception: " + e.getMessage());
            for (Throwable suppressed : e.getSuppressed()) {
                System.out.println("    suppressed during resource close: " + suppressed.getMessage());
            }
        }
    }
}
