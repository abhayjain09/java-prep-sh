package com.interviewprep.orders.concurrency;

import com.interviewprep.orders.domain.InsufficientStockException;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Transfers stock between two {@link Warehouse}s. Each warehouse is guarded
 * by its own lock, and a transfer must hold BOTH locks for its duration (you
 * can't let another thread see "stock removed from A" without "stock added
 * to B" in between — that's the same all-or-nothing requirement {@code
 * OrderService.placeOrder} enforces across multiple SKUs in Module 1).
 * Needing two locks at once is exactly the shape that creates deadlock risk,
 * and this class deliberately contains both the buggy version and two
 * different fixes so you can compare them directly.
 *
 * See {@code diagrams/deadlock-sequence.md} for the interleaving diagram of
 * the buggy version below actually deadlocking.
 */
public class StockTransferService {

    /**
     * THE BUG: locks {@code from} first, then {@code to} — i.e. whatever
     * order the CALLER happens to pass arguments in. If thread 1 calls
     * {@code transferUnsafe(east, west, ...)} while thread 2 concurrently
     * calls {@code transferUnsafe(west, east, ...)}, we have:
     * <pre>
     *   Thread 1: lock(east)  ... waiting for lock(west)
     *   Thread 2: lock(west)  ... waiting for lock(east)
     * </pre>
     * Thread 1 holds east and wants west; thread 2 holds west and wants
     * east. Neither can ever proceed — a circular wait, the textbook
     * definition of deadlock. {@code holdDelayMillis} exists ONLY to make
     * this demo reliably reproduce the deadlock (by widening the window
     * between acquiring the first lock and attempting the second) instead of
     * depending on the two threads happening to interleave just right —
     * identical in spirit to why {@link InventoryStressTester} uses many
     * threads and iterations to reliably surface a race condition. Never
     * write a deliberate {@code Thread.sleep} while holding a lock in real
     * production code — it's here purely to make an intermittent bug
     * observable on demand.
     */
    public void transferUnsafe(Warehouse from, Warehouse to, String sku, int quantity, long holdDelayMillis) {
        from.lock().lock();
        try {
            sleepQuietly(holdDelayMillis);
            to.lock().lock();
            try {
                doTransfer(from, to, sku, quantity);
            } finally {
                to.lock().unlock();
            }
        } finally {
            from.lock().unlock();
        }
    }

    /**
     * FIX #1 — CONSISTENT LOCK ORDERING. Every caller, regardless of which
     * warehouse is logically the source or destination, acquires locks in
     * the SAME global order — here, by comparing {@link Warehouse#id()}
     * (any stable, total ordering works equally well: {@code
     * System.identityHashCode()} tie-broken, a numeric warehouse ID, etc.).
     * With a fixed global lock order, circular wait is structurally
     * impossible: if every thread that needs both locks A and B always
     * takes A before B, no thread can ever be holding B while waiting for A
     * — one direction of the circular dependency simply cannot occur.
     * <p>
     * Note the LOCK order (first/second below) is independent of the
     * TRANSFER direction (from/to) — we still move stock from {@code from}
     * to {@code to} exactly as requested, we just decide WHICH lock to
     * reach for first based on identity, not on argument order.
     */
    public void transferOrdered(Warehouse from, Warehouse to, String sku, int quantity) {
        Warehouse first = from.id().compareTo(to.id()) <= 0 ? from : to;
        Warehouse second = (first == from) ? to : from;

        first.lock().lock();
        try {
            second.lock().lock();
            try {
                doTransfer(from, to, sku, quantity);
            } finally {
                second.lock().unlock();
            }
        } finally {
            first.lock().unlock();
        }
    }

    /**
     * FIX #2 — TRYLOCK WITH TIMEOUT, as an alternative to ordering (useful
     * when you genuinely can't establish a global order — e.g. the two
     * resources come from different subsystems with no shared identity
     * scheme). Instead of blocking indefinitely on the second lock (which is
     * what creates deadlock), we bound the wait. If we can't get BOTH locks
     * within the timeout, we release whatever we're holding and either give
     * up (returning {@code false}) or retry — critically, we never sit
     * there holding one lock while blocking forever for the other.
     * <p>
     * The random backoff before retrying matters: without it, two threads
     * that both fail to get the second lock could release, retry, and
     * collide again in perfect lockstep forever (a LIVELOCK — both threads
     * are active and "making progress" in the sense of doing work, but
     * neither ever completes). Randomizing the backoff makes that
     * synchronization vanishingly unlikely.
     */
    public boolean transferWithTimeout(Warehouse from, Warehouse to, String sku, int quantity,
                                        Duration overallTimeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + overallTimeout.toNanos();

        while (System.nanoTime() < deadlineNanos) {
            ReentrantLock fromLock = from.lock();
            if (fromLock.tryLock(50, TimeUnit.MILLISECONDS)) {
                try {
                    ReentrantLock toLock = to.lock();
                    if (toLock.tryLock(50, TimeUnit.MILLISECONDS)) {
                        try {
                            doTransfer(from, to, sku, quantity);
                            return true;
                        } finally {
                            toLock.unlock();
                        }
                    }
                    // Couldn't get the second lock in time — release the
                    // first immediately rather than keep holding it. Holding
                    // "from" while blocking indefinitely for "to" is exactly
                    // the deadlock shape we're avoiding; releasing and
                    // retrying trades a small amount of wasted work for the
                    // guarantee that we never block forever.
                } finally {
                    fromLock.unlock();
                }
            }
            Thread.sleep(ThreadLocalRandom.current().nextInt(5, 25));
        }
        return false; // gave up within the timeout budget; caller decides how to react
    }

    private void doTransfer(Warehouse from, Warehouse to, String sku, int quantity) {
        int available = from.stockOfUnguarded(sku);
        if (available < quantity) {
            throw new InsufficientStockException(sku, quantity, available);
        }
        from.setStockUnguarded(sku, available - quantity);
        to.restockUnguarded(sku, quantity);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
