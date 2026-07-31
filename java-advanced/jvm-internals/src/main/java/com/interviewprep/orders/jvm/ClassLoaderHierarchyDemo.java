package com.interviewprep.orders.jvm;

import com.interviewprep.orders.domain.Order;

/**
 * Prints the classloader chain that loads this module's own classes and the
 * java-basics domain classes, then deterministically reproduces both
 * ClassNotFoundException and NoClassDefFoundError so the practical
 * difference between them is something you've SEEN, not just read about.
 *
 * See diagrams/classloader-hierarchy.md for the delegation-model picture
 * this class's first method narrates.
 */
public class ClassLoaderHierarchyDemo {

    /**
     * A nested class whose static initializer deliberately throws. This is
     * a real, deterministic, reproducible way to trigger the
     * ExceptionInInitializerError -> NoClassDefFoundError sequence — it is
     * NOT a fabricated log or made-up output; every JVM implementing the
     * JLS behaves exactly this way (JLS 12.4.2: once a class fails to
     * initialize, it is marked erroneous PERMANENTLY, and every subsequent
     * active use throws NoClassDefFoundError instead of re-running
     * <clinit>).
     */
    static class PoisonedAtInit {
        static final int VALUE = computeOrThrow();

        private static int computeOrThrow() {
            throw new RuntimeException("simulated static-init failure (e.g. a bad config value)");
        }
    }

    public static void main(String[] args) {
        printClassLoaderChain();
        demonstrateClassNotFoundException();
        demonstrateNoClassDefFoundErrorViaFailedStaticInit();
    }

    private static void printClassLoaderChain() {
        System.out.println("=== Classloader hierarchy ===");

        ClassLoader appLoader = ClassLoaderHierarchyDemo.class.getClassLoader();
        System.out.println("ClassLoaderHierarchyDemo (this module) loaded by: " + appLoader);

        ClassLoader domainLoader = Order.class.getClassLoader();
        System.out.println("Order (java-basics domain class, same classpath) loaded by: " + domainLoader);
        System.out.println("Same loader instance for both? " + (appLoader == domainLoader));

        // Walk up the parent chain from the Application classloader to the
        // Bootstrap classloader. Bootstrap has no Java-level object, so the
        // walk necessarily terminates at null.
        ClassLoader current = appLoader;
        int depth = 0;
        while (current != null) {
            System.out.println("  chain[" + depth + "] = " + current);
            current = current.getParent();
            depth++;
        }
        System.out.println("  chain[" + depth + "] = null  (this null IS the Bootstrap classloader —"
                + " it has no Java-level representation)");

        // java.lang.String is loaded by the BOOTSTRAP classloader as part
        // of the java.base module — its getClassLoader() is always null,
        // on every JVM, regardless of your application's classpath.
        System.out.println("String.class.getClassLoader() = " + String.class.getClassLoader()
                + "  (confirms java.lang.* comes from Bootstrap)");
    }

    private static void demonstrateClassNotFoundException() {
        System.out.println();
        System.out.println("=== ClassNotFoundException demo (explicit, by-name lookup) ===");
        try {
            // Class.forName is an EXPLICIT request: "find and load a class
            // with exactly this fully-qualified name." This class name is
            // fabricated for the demo and genuinely does not exist
            // anywhere on the classpath, at any level of the delegation
            // chain — so every classloader consulted fails, and the
            // ORIGINAL requester (this call site) gets told so directly,
            // as a checked exception it must handle or declare.
            Class.forName("com.interviewprep.orders.domain.ThisClassDoesNotExist");
            System.out.println("unreachable");
        } catch (ClassNotFoundException e) {
            System.out.println("Caught ClassNotFoundException as expected: " + e.getMessage());
            System.out.println("  -> Real-world trigger: Class.forName(\"oracle.jdbc.OracleDriver\")"
                    + " when the Oracle JDBC driver jar isn't on the classpath.");
        }
    }

    private static void demonstrateNoClassDefFoundErrorViaFailedStaticInit() {
        System.out.println();
        System.out.println("=== NoClassDefFoundError demo (implicit, via a poisoned static initializer) ===");

        try {
            // FIRST touch of PoisonedAtInit: the JVM runs its <clinit>
            // (static initializer) for the first time, which throws. The
            // JVM wraps that in ExceptionInInitializerError.
            int firstTouch = PoisonedAtInit.VALUE;
            System.out.println("unreachable: " + firstTouch);
        } catch (ExceptionInInitializerError e) {
            System.out.println("First touch -> ExceptionInInitializerError, cause: " + e.getCause());
        }

        try {
            // SECOND touch: PoisonedAtInit is now permanently marked
            // "erroneous" by the JVM. It will NEVER attempt <clinit>
            // again. Every future reference -- from anywhere in the
            // program -- throws NoClassDefFoundError instead, and the
            // ORIGINAL RuntimeException is gone from this stack trace
            // (only the class name and "Could not initialize" remain) --
            // which is exactly why NoClassDefFoundError in production logs
            // is often frustratingly uninformative: the real root cause
            // was logged (if at all) only once, at the moment of the
            // FIRST touch, possibly during a different request entirely.
            int secondTouch = PoisonedAtInit.VALUE;
            System.out.println("unreachable: " + secondTouch);
        } catch (NoClassDefFoundError e) {
            System.out.println("Second touch -> NoClassDefFoundError as expected: " + e.getMessage());
            System.out.println("  -> Real-world trigger: a class with a static block that reads a config "
                    + "file/env var failed once at startup; every later use of that class anywhere in "
                    + "the app throws NoClassDefFoundError, which looks unrelated to the real cause "
                    + "unless you find the FIRST ExceptionInInitializerError in the logs.");
        }

        System.out.println();
        System.out.println("PRACTICAL DIFFERENCE, in one line: ClassNotFoundException means \"I looked "
                + "everywhere and this class was never on the classpath\" (a packaging/dependency "
                + "problem); NoClassDefFoundError means \"this class WAS available and usable at some "
                + "point, but a required use of it just failed\" (a partial-deploy, classpath-changed-"
                + "at-runtime, or failed-static-initializer problem).");
    }
}
