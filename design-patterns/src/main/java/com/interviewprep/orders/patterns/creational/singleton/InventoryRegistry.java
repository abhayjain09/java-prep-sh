package com.interviewprep.orders.patterns.creational.singleton;

import com.interviewprep.orders.domain.Inventory;

/**
 * CORRECT (mechanically) — a thread-safe Singleton using the
 * "initialization-on-demand holder" idiom, which relies on the JVM's
 * class-loading guarantees instead of hand-rolled locking.
 *
 * HOW IT WORKS: the JVM guarantees a class is initialized (its static fields
 * set) exactly once, lazily, the first time it's referenced, and that this
 * initialization is thread-safe by specification (JLS 12.4.2) — no explicit
 * {@code synchronized} needed. {@code Holder} is a nested class, so it isn't
 * loaded until {@code getInstance()} references {@code Holder.INSTANCE} for
 * the first time. This fixes {@link NaiveInventoryRegistry}'s bug 1 (race
 * condition) completely, at zero synchronization cost on every subsequent call.
 *
 * An alternative, arguably even simpler, thread-safe idiom is a single-element
 * {@code enum}:
 * <pre>{@code
 * public enum InventoryRegistryEnum {
 *     INSTANCE;
 *     private final Inventory inventory = new Inventory();
 *     public Inventory inventory() { return inventory; }
 * }
 * }</pre>
 * Effective Java (Bloch, Item 3) recommends the enum form because it also
 * gives you serialization safety for free (a hand-written Singleton needs a
 * {@code readResolve()} to prevent deserialization from creating a second
 * instance — the enum form can't be broken this way even via reflection,
 * which can otherwise call a private constructor directly).
 *
 * ============================================================================
 * WHY THIS IS STILL OFTEN THE WRONG ANSWER IN MODERN JAVA — READ THIS PART
 * ============================================================================
 * Fixing the thread-safety bug does NOT fix bug 2 and 3 from
 * {@link NaiveInventoryRegistry}'s Javadoc: this is still hidden global
 * mutable state, and it is still hard to substitute a fake in a test (you'd
 * need reflection, or a test-only reset method that leaks test concerns into
 * production code).
 *
 * The actual senior-level answer interviewers are listening for is usually:
 * "I wouldn't hand-write a Singleton at all — I'd let a Dependency Injection
 * container own the single instance, and inject it explicitly." Module 5
 * (Spring) replaces this entire class with:
 * <pre>{@code
 * @Bean
 * public Inventory inventory() {
 *     return new Inventory();
 * }
 * }</pre>
 * Spring's IoC container defaults every bean to singleton SCOPE — one shared
 * instance per application context — but *without* any of this class's
 * problems:
 *   - The dependency is explicit in every consumer's constructor
 *     ({@code OrderService(Inventory inventory)}), not hidden behind a static
 *     call — this is the Dependency Inversion Principle (see SOLID.md) in
 *     practice.
 *   - Tests can construct a fresh {@code Inventory} (or a Mockito mock) and
 *     pass it directly, with zero shared state between tests.
 *   - Swapping the implementation (e.g. a {@code RedisBackedInventory} in
 *     production vs. an in-memory one in tests) is a constructor-injection
 *     concern, not a static-field concern.
 *
 * RULE OF THUMB for interviews: Singleton (the GoF pattern, hand-rolled) is
 * appropriate for framework-free utility code with a genuinely process-wide
 * resource (e.g. a logging manager, a JVM-wide thread pool wrapper) where no
 * DI container is present. Once a DI container exists, prefer "singleton
 * scope managed by the container" over "Singleton pattern written by hand" —
 * same runtime shape (one instance), completely different testability and
 * coupling story.
 */
public final class InventoryRegistry {

    private final Inventory inventory = new Inventory();

    // Private constructor: the only place this class is instantiated is the
    // Holder below, so there is no way for external code to call `new
    // InventoryRegistry()` and accidentally create a second instance.
    private InventoryRegistry() {
    }

    // Not loaded/initialized by the JVM until Holder.INSTANCE is first
    // referenced inside getInstance() — this is what makes the laziness
    // thread-safe without an explicit lock.
    private static final class Holder {
        private static final InventoryRegistry INSTANCE = new InventoryRegistry();
    }

    public static InventoryRegistry getInstance() {
        return Holder.INSTANCE;
    }

    public Inventory inventory() {
        return inventory;
    }
}
