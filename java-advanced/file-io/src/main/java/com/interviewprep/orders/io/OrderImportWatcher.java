package com.interviewprep.orders.io;

import com.interviewprep.orders.domain.Order;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Watches a directory for newly-created files and treats each one as a CSV
 * order-import batch — e.g. an ops process (or an upstream system) drops
 * {@code orders-2026-07-31.csv} into an {@code incoming/} folder and it gets
 * picked up and imported automatically, instead of a human running the
 * import by hand, or a cron job polling the directory every minute (which
 * either wastes CPU polling too often, or adds up-to-a-minute latency
 * polling too rarely).
 *
 * WatchService is the OS-native alternative to polling: on Linux it's
 * backed by {@code inotify}, on Windows by {@code ReadDirectoryChangesW},
 * and on macOS by a kqueue-based implementation (with a small inherent
 * latency on some JDK/macOS combinations — worth knowing if a demo "feels" a
 * beat slower on macOS than on Linux). The JDK gives one uniform API over
 * all three, instead of a hand-rolled polling loop that calls
 * {@code Files.list()} on a timer.
 */
public final class OrderImportWatcher {

    private OrderImportWatcher() {
        // no instances — pure static utility
    }

    /**
     * Bounded, demo-friendly form: watches {@code dir} for up to
     * {@code timeout}, invoking {@code onBatchImported} once per new file
     * detected, then returns when the timeout elapses. Production code would
     * run essentially the same loop with no deadline, for the life of the
     * application (see the loop shape below — removing the
     * {@code Instant.now().isBefore(deadline)} check and calling
     * {@code watchService.take()} instead of the timed {@code poll()} turns
     * this into that long-running form).
     *
     * IMPORTANT: only files created AFTER this method registers the watch
     * generate events. A file already sitting in {@code dir} before this
     * call will NOT be picked up — a real "watch this folder on startup"
     * feature needs an explicit initial directory scan (e.g.
     * {@code Files.list(dir)}) in addition to registering the watch, to
     * catch anything dropped while nothing was watching.
     */
    public static void watchOnce(Path dir, Duration timeout, Consumer<List<Order>> onBatchImported) throws IOException {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

            Instant deadline = Instant.now().plus(timeout);
            while (true) {
                long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
                if (remainingMillis <= 0) {
                    break; // demo timeout reached — a real long-running watcher would loop forever instead
                }

                WatchKey key;
                try {
                    // Timed poll rather than the blocking take(): lets this
                    // demo respect the overall deadline instead of blocking
                    // forever if no file ever arrives. Production code
                    // watching indefinitely would typically use take().
                    key = watchService.poll(remainingMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (key == null) {
                    continue; // nothing happened within this poll window; loop re-checks the deadline
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        // OVERFLOW means events were dropped because they
                        // arrived faster than they could be consumed.
                        // Production code watching a high-traffic directory
                        // should treat this as "re-scan the directory from
                        // scratch, don't trust the event stream alone" —
                        // WatchService makes a best effort, not a guarantee
                        // every single event is delivered.
                        System.out.println("  WatchService overflow — some file-creation events may have been missed.");
                        continue;
                    }

                    @SuppressWarnings("unchecked") // ENTRY_CREATE events always carry a Path context
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path createdFile = dir.resolve(pathEvent.context());

                    System.out.println("  detected new file: " + createdFile.getFileName());
                    try {
                        List<Order> imported = CsvOrderImporter.importOrders(createdFile);
                        onBatchImported.accept(imported);
                    } catch (IOException e) {
                        System.err.println("  failed to import " + createdFile + ": " + e.getMessage());
                    }
                }

                // The WatchKey MUST be reset after its events are processed,
                // or this directory silently stops receiving further events
                // — one of the most common WatchService bugs (works once,
                // then goes quiet with no error).
                boolean stillValid = key.reset();
                if (!stillValid) {
                    break; // the watched directory itself became inaccessible (e.g. deleted)
                }
            }
        }
    }
}
