package com.interviewprep.orders.jvm;

/*
 * ============================================================================
 * THIS FILE REQUIRES JMH (java.openjdk.jmh) TO COMPILE AND RUN. IT WILL NOT
 * COMPILE AS PART OF THIS MODULE'S PLAIN `javac` BUILD.
 * ============================================================================
 *
 * This repo (see repo root README.md) has no Maven/Gradle build anywhere
 * yet, and this sandbox has neither `java`, `mvn`, nor internet access to
 * fetch dependencies. JMH benchmarks are normally built as their OWN Maven
 * module (the JMH team's official archetype generates exactly this shape)
 * with a pom.xml containing:
 *
 *   <dependency>
 *     <groupId>org.openjdk.jmh</groupId>
 *     <artifactId>jmh-core</artifactId>
 *     <version>1.37</version>
 *   </dependency>
 *   <dependency>
 *     <groupId>org.openjdk.jmh</groupId>
 *     <artifactId>jmh-generator-annprocess</artifactId>
 *     <version>1.37</version>
 *     <scope>provided</scope>
 *   </dependency>
 *
 * plus the `maven-shade-plugin` configured with JMH's `mainClass` set to
 * `org.openjdk.jmh.Main`, so `mvn clean package` produces a runnable
 * `benchmarks.jar`. None of that exists in this repo today (Module 5 is
 * where Maven is introduced project-wide) — this class is included as an
 * ACCURATE SKELETON of the real annotations/structure you'd write, to be
 * copy-pasted into a real `jmh-benchmarks/` Maven module once one exists,
 * not as something you can run today.
 *
 * The imports below are commented out for exactly this reason — uncomment
 * them once this file lives in a module with the JMH dependency on its
 * classpath.
 *
 * import org.openjdk.jmh.annotations.Benchmark;
 * import org.openjdk.jmh.annotations.BenchmarkMode;
 * import org.openjdk.jmh.annotations.Fork;
 * import org.openjdk.jmh.annotations.Measurement;
 * import org.openjdk.jmh.annotations.Mode;
 * import org.openjdk.jmh.annotations.OutputTimeUnit;
 * import org.openjdk.jmh.annotations.Scope;
 * import org.openjdk.jmh.annotations.Setup;
 * import org.openjdk.jmh.annotations.State;
 * import org.openjdk.jmh.annotations.Warmup;
 * import org.openjdk.jmh.infra.Blackhole;
 * import org.openjdk.jmh.runner.Runner;
 * import org.openjdk.jmh.runner.RunnerException;
 * import org.openjdk.jmh.runner.options.Options;
 * import org.openjdk.jmh.runner.options.OptionsBuilder;
 *
 * import com.interviewprep.orders.domain.Customer;
 * import com.interviewprep.orders.domain.Order;
 * import com.interviewprep.orders.domain.OrderLine;
 * import com.interviewprep.orders.domain.Product;
 * import com.interviewprep.orders.domain.Inventory;
 * import com.interviewprep.orders.service.OrderService;
 *
 * import java.math.BigDecimal;
 * import java.util.ArrayList;
 * import java.util.List;
 * import java.util.concurrent.TimeUnit;
 *
 *
 * // The real class, uncommented, would look like this:
 * //
 * // @BenchmarkMode(Mode.AverageTime)               // measure ns/op (or us/op) per call, not ops/sec
 * // @OutputTimeUnit(TimeUnit.MICROSECONDS)          // report in microseconds -- readable for this workload's scale
 * // @Warmup(iterations = 5, time = 1)               // 5 warm-up iterations, 1 second each, DISCARDED from results
 * // @Measurement(iterations = 5, time = 1)          // 5 measured iterations, 1 second each, averaged
 * // @Fork(value = 2)                                 // run in 2 completely fresh JVM processes, average across them
 * // public class OrderServiceJmhBenchmark {
 * //
 * //     // @State(Scope.Benchmark): ONE instance of this state is shared
 * //     // across all threads/iterations of a single fork -- exactly what
 * //     // we want here, since OrderService/customer/orders are read-only
 * //     // fixtures for this comparison, not something each benchmark
 * //     // invocation should rebuild (rebuilding them per invocation would
 * //     // benchmark object construction, not the two methods under test).
 * //     @State(Scope.Benchmark)
 * //     public static class OrderServiceState {
 * //         OrderService orderService;
 * //         Customer customer;
 * //         List<Order> orders;
 * //
 * //         // @Setup(Level.Trial) (the default Level) runs ONCE per fork,
 * //         // before any warm-up or measurement iterations -- this is
 * //         // where fixture construction belongs, NOT inside @Benchmark
 * //         // methods, so it's never counted in the measured time.
 * //         @Setup
 * //         public void setUp() {
 * //             Inventory inventory = new Inventory();
 * //             inventory.restock("SKU-LAPTOP", 1_000_000);
 * //             inventory.restock("SKU-MOUSE", 1_000_000);
 * //             orderService = new OrderService(inventory);
 * //
 * //             customer = new Customer("CUST-1", "Ada Lovelace", "ada@example.com");
 * //             Product laptop = new Product("SKU-LAPTOP", "Laptop", new BigDecimal("1200.00"));
 * //             Product mouse = new Product("SKU-MOUSE", "Wireless Mouse", new BigDecimal("25.00"));
 * //
 * //             orders = new ArrayList<>();
 * //             for (int i = 0; i < 50; i++) {
 * //                 Order order = new Order("ORD-" + i, customer);
 * //                 order.addLine(new OrderLine(laptop, 1));
 * //                 order.addLine(new OrderLine(mouse, 2));
 * //                 orders.add(order);
 * //             }
 * //         }
 * //     }
 * //
 * //     // Returning the BigDecimal result (instead of void, and instead
 * //     // of discarding it) is itself an anti-dead-code-elimination
 * //     // measure: JMH's generated harness code consumes the return value
 * //     // of every @Benchmark method automatically. Blackhole.consume(...)
 * //     // (shown in the second variant below) is the more explicit form,
 * //     // needed when a method produces MULTIPLE values worth keeping live
 * //     // or no return value at all.
 * //     @Benchmark
 * //     public BigDecimal imperative(OrderServiceState state) {
 * //         return state.orderService.totalSpentByImperative(state.customer, state.orders);
 * //     }
 * //
 * //     @Benchmark
 * //     public BigDecimal streams(OrderServiceState state) {
 * //         return state.orderService.totalSpentByStreams(state.customer, state.orders);
 * //     }
 * //
 * //     // Explicit Blackhole variant -- prefer this shape once a benchmark
 * //     // method needs to exercise a void-returning method, or computes
 * //     // more than one value you want to guarantee stays live:
 * //     @Benchmark
 * //     public void groupingBy(OrderServiceState state, Blackhole blackhole) {
 * //         blackhole.consume(state.orderService.ordersByStatus(state.orders));
 * //     }
 * //
 * //     // Optional: a main() so this benchmark can be run directly (java
 * //     // -jar benchmarks.jar OrderServiceJmhBenchmark also works without
 * //     // this, via JMH's generated Main class, once built with the shade
 * //     // plugin).
 * //     public static void main(String[] args) throws RunnerException {
 * //         Options options = new OptionsBuilder()
 * //                 .include(OrderServiceJmhBenchmark.class.getSimpleName())
 * //                 .build();
 * //         new Runner(options).run();
 * //     }
 * // }
 *
 *
 * HOW YOU'D ACTUALLY RUN THIS (once it lives in a proper JMH Maven module):
 *
 *   mvn clean package
 *   java -jar target/benchmarks.jar OrderServiceJmhBenchmark
 *
 * Expected shape of the output (a results table with columns Benchmark,
 * Mode, Cnt, Score, Error, Units) -- NOT reproduced here as fake numbers,
 * since this sandbox cannot execute JMH and any numbers shown would be
 * fabricated. What you SHOULD expect qualitatively, based on README section
 * 4's discussion of streams vs. loops: for a small (dozens of elements)
 * `orders` list like the fixture above, the imperative and streams versions
 * should land within noise of each other once both are JIT-warmed --
 * streams' per-element overhead (lambda dispatch, an internal Spliterator)
 * only becomes measurable at much larger element counts or in a much
 * hotter loop. If a real run showed a LARGE gap on this small fixture, that
 * would itself be a signal to double-check the benchmark's warm-up
 * configuration before trusting the number.
 */
public class OrderServiceJmhBenchmark {
    // Intentionally empty: see the extensive block comment above. This
    // class exists as a documented placeholder/skeleton, not runnable code,
    // because the JMH dependency this file needs is unavailable in this
    // repo and this sandbox. Do not add real (non-commented) JMH-annotated
    // code here until a Maven module with the jmh-core /
    // jmh-generator-annprocess dependencies exists to compile it against.
}
