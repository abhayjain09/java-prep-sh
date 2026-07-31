package com.interviewprep.orders.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Demonstrates {@link FileChannel#lock()} protecting a "single writer at a
 * time" export — e.g. a nightly inventory-snapshot export job that must
 * never have two instances (accidentally scheduled twice, or a slow run
 * overlapping the next) write to the same output file concurrently and
 * interleave/corrupt its contents.
 *
 * WHAT THIS DOES PROTECT AGAINST: another well-behaved OS PROCESS — which
 * could be a completely separate JVM, or even a program written in another
 * language — that ALSO calls {@code FileChannel.lock()}/{@code tryLock()} on
 * the same file before writing to it. Java's file locks map directly onto
 * the underlying OS's advisory locking facility ({@code fcntl}/{@code flock}
 * on POSIX systems, {@code LockFileEx} on Windows).
 *
 * WHAT THIS DOES NOT PROTECT AGAINST:
 * <ol>
 *   <li><b>Uncooperative writers.</b> Advisory locks only stop OTHER CODE
 *       THAT ALSO CHECKS THE LOCK. A process that just opens the file and
 *       writes to it without calling {@code lock()} first is completely
 *       unaffected — there is no way to force a writer to respect the
 *       lock.</li>
 *   <li><b>Multiple threads inside the SAME JVM.</b> The JDK explicitly
 *       forbids a second overlapping {@code FileLock} on the same file from
 *       the same JVM — attempting it throws
 *       {@code OverlappingFileLockException} immediately rather than
 *       blocking the second thread. That means this class is a coordination
 *       mechanism BETWEEN PROCESSES, not a substitute for Java-level
 *       locking (a plain {@code synchronized} block, a
 *       {@code ReentrantLock}) between threads within one process — that
 *       topic is previewed here and given full treatment in Module 3
 *       (Concurrency).</li>
 *   <li><b>Network filesystems.</b> Locking semantics over NFS (and some
 *       cloud/mounted filesystems) are notoriously unreliable or
 *       best-effort. Don't rely on {@code FileChannel} locking as the only
 *       safety net for a shared network export directory in production —
 *       use a proper distributed coordination mechanism instead (a database
 *       row lock, a lease service, an S3 conditional-write, etc).</li>
 * </ol>
 */
public final class ExportFileLocker {

    private ExportFileLocker() {
        // no instances — pure static utility
    }

    /**
     * Opens (creating if necessary, truncating any previous content) and
     * writes {@code content} to {@code file}, holding an exclusive
     * whole-file lock for the duration of the write.
     */
    public static void exportWithLock(Path file, String content) throws IOException {
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            // lock() with no arguments takes an exclusive lock covering the
            // WHOLE file (position 0, size Long.MAX_VALUE, shared = false)
            // and BLOCKS the calling thread until it's available.
            // tryLock() is the non-blocking sibling — it returns null
            // immediately if another lock-holder already has it, useful
            // when "skip this run, someone else is already exporting" beats
            // waiting indefinitely.
            try (FileLock lock = channel.lock()) {
                System.out.println("  acquired exclusive lock on " + file.getFileName());

                ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                // The lock is released automatically here, when this
                // try-with-resources block exits (FileLock implements
                // AutoCloseable). It would ALSO be released implicitly if
                // the enclosing channel closed first, but relying on that
                // instead of closing the lock explicitly is a common source
                // of "why is this file still locked" bugs when code paths
                // change later — always close what you explicitly acquired.
            }
        }
    }
}
