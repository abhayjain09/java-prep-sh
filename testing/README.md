# Module 10 — Testing (JUnit 5, Mockito, AssertJ, Testcontainers)

**Domain used throughout:** the same Order/Inventory system from
[java-basics/](../java-basics/) (Module 1) — `Customer`, `Product`, `Order`,
`OrderLine`, `OrderStatus`, `Inventory`, `InsufficientStockException`,
`OrderService`. This module writes no new production code; it writes tests
*against* that existing code, imported directly (see "Why `java-basics` is a
source root, not a dependency" below for how that's wired up without a proper
Maven multi-module reactor).

Companion files:
- [diagrams/test-pyramid.md](diagrams/test-pyramid.md) — unit vs. integration vs. end-to-end, applied to this module's actual test files
- [diagrams/rollback-sequence.md](diagrams/rollback-sequence.md) — sequence diagram of the mocked `OrderServiceTest` rollback scenario
- [pom.xml](pom.xml) — the Maven project (first one in this repo — `java-basics` predates Maven entirely)
- [src/test/java/](src/test/java/com/interviewprep/orders/) — the actual tests
- [EXPLANATION.md](EXPLANATION.md) — line-by-line walkthrough of every test file
- [EXERCISES.md](EXERCISES.md) — hands-on exercises
- [INTERVIEW.md](INTERVIEW.md) — beginner/intermediate/senior/scenario interview questions with ideal answers

---

## 1. The Test Pyramid (Unit vs. Integration vs. End-to-End)

### What it is
A model for how a healthy test suite's effort should be distributed across three
tiers, by how much of the real system each tier exercises:

- **Unit tests** — one class, in isolation from its collaborators (real or mocked out). `InventoryTest`, `OrderTest`, `OrderStatusTest`, `OrderServiceTest` in this module.
- **Integration tests** — a real boundary the code depends on (a database, a message broker, another service) exercised for real, but usually not the whole running application. `OrderRepositoryIT` in this module (real PostgreSQL via Testcontainers).
- **End-to-end (E2E) tests** — the whole system, wired together as close to production shape as practical, verifying a user-facing scenario top to bottom. None in this module — see [diagrams/test-pyramid.md](diagrams/test-pyramid.md) for why.

See [diagrams/test-pyramid.md](diagrams/test-pyramid.md) for the visual and the
counts/speed table.

### Why introduced / problem it solves
Without a deliberate shape, teams drift toward one of two failure modes: the
**"ice cream cone"** (mostly E2E tests, a thin sliver of unit tests) — slow,
flaky, expensive to maintain, and a failure tells you almost nothing about
*where* the bug is; or **no tests below "does it run"** at all, where every
regression is caught by a human in QA or, worse, a customer in production. The
pyramid shape (many fast unit tests, some integration tests, few E2E tests)
optimizes for both **fast feedback** (most of the suite runs in seconds) and
**real confidence at the boundaries that matter most** (the few integration/E2E
tests target exactly the places pure unit tests can't see: does this SQL
actually work against this database engine, does this HTTP client actually
parse this response).

### When to use which tier
- Reach for a **unit test** by default — for any pure logic, any single class's
  behavior, any business rule (`OrderStatus.canTransitionTo`, `Inventory.reserve`'s
  boundary case). If a collaborator is expensive, slow, non-deterministic, or you
  need to simulate a failure that's awkward to trigger for real, mock it
  (`OrderServiceTest` mocking `Inventory` is the textbook case — see section 3).
- Reach for an **integration test** when the thing you're actually unsure about
  is "does my code's assumption about an external system hold" — a specific SQL
  query against a specific database engine, a specific serialization format
  against a specific message broker. You don't need many of these per boundary;
  a handful covering the realistic query/constraint shapes is usually enough.
- Reach for an **E2E test** sparingly, for the handful of scenarios that most
  matter to the business (e.g. "a customer can place an order and see it
  confirmed") — not for every code path, which unit tests already cover more
  cheaply.

### When NOT to use each tier
- Don't write a unit test that mocks *everything*, including simple, cheap value
  objects (`Customer`, `Product`, `OrderLine`) — see section 3's "common
  mistakes" for why that's a smell, not a best practice.
- Don't write an integration test for logic that has no real external boundary
  involved — if nothing crosses into a database/network/filesystem, it's a unit
  test wearing an integration test's cost (seconds instead of milliseconds) for
  no benefit.
- Don't reach for E2E to catch bugs a unit test would have caught faster and
  more precisely — "let's add an E2E test" is sometimes really "we don't trust
  our unit tests," which is a unit-test coverage problem, not an E2E problem.

### Trade-offs & performance implications
- Unit tests are cheap enough to run on every save; a CI pipeline can run
  thousands of them in seconds. Integration tests cost real seconds each
  (container start-up, actual network round-trips) — bearable in the tens, a
  real drag in the hundreds unless containers are reused across test classes.
  E2E tests cost the most and are the most prone to environmental flakiness
  (timing, external service availability) — the ROI per test written drops
  sharply as you move up the pyramid.
- A common mid-size-project number: a few thousand unit tests running in under
  a minute, a few dozen to low-hundreds of integration tests running in a few
  minutes, and a few dozen E2E tests running in tens of minutes in a separate
  CI stage.

### Enterprise examples
- A payments platform running its full unit suite on every commit (fast
  feedback loop for developers), its integration suite (Testcontainers-backed
  Postgres/Kafka) on every PR before merge, and a smaller E2E smoke suite
  against a staging environment before each production deploy — three
  different gates, three different costs, three different confidence levels.

### Common mistakes
- Treating "integration test" and "slow test" as interchangeable and therefore
  writing everything as a unit test with heavy mocking just to keep things
  fast — this loses real coverage of the boundaries that actually break in
  production (a SQL query that's syntactically fine but semantically wrong
  against the real engine).
- Letting E2E test count grow unchecked because "more coverage is always
  better" — past a certain point, a slow, flaky E2E suite gets skipped or
  ignored by the team, which is worse than not having it, because it creates
  false confidence that "the tests pass" when the tests aren't actually being
  looked at.

---

## 2. JUnit 5

### What it is
The de facto standard testing framework for the JVM. JUnit 5 ("Jupiter") is a
ground-up rewrite of JUnit 4, split into three parts: the **Platform** (launches
tests, integrates with build tools/IDEs), **Jupiter** (the new
programming/extension model you write tests against — `@Test`, `@BeforeEach`,
`@ParameterizedTest`, etc.), and **Vintage** (runs old JUnit 3/4 tests on the
Platform, not used in this module since there's no legacy JUnit 4 code here).

### Why introduced / problem it solves
JUnit 4 had one extension mechanism (`@RunWith`, one per test class — you
couldn't combine a `Parameterized` runner and a `Mockito` runner on the same
class without workarounds) and no first-class parameterized test support beyond
a fairly clunky `@RunWith(Parameterized.class)`. JUnit 5's `@ExtendWith` supports
*multiple, composable* extensions per class (this module combines
`@ExtendWith(MockitoExtension.class)` on `OrderServiceTest` with everything else
Jupiter provides), and `@ParameterizedTest` is a first-class, much richer
feature (`@CsvSource`, `@MethodSource`, `@EnumSource`, and more).

### Features this module demonstrates, and why each exists
- **`@ParameterizedTest` + `@CsvSource`** (`OrderStatusTest`): runs the same test
  method once per row of tabular data. Exists so testing a lookup table/decision
  matrix (here, all 25 `OrderStatus` transition pairs) doesn't require one
  hand-written `@Test` method per row — see that file's Javadoc for the full
  rationale.
- **`@Nested`** (`InventoryTest`, `OrderServiceTest`): lets you group related
  tests into inner classes (`ReserveTests`, `PlaceOrderRollback`, ...), each
  showing up as its own named group in IDE/CI test reports. Exists so a test
  class with many methods reads as organized sections instead of one long flat
  list — especially valuable in `OrderServiceTest`, where "happy path" vs.
  "rollback" vs. "reporting methods" are genuinely different concerns worth
  separating visually and in test reports.
- **Lifecycle annotations `@BeforeEach`/`@BeforeAll`/`@AfterAll`**:
  `@BeforeEach` (every domain test class) creates fresh, isolated fixtures per
  test method — critical for `InventoryTest` specifically, since `Inventory` is
  mutable and sharing one instance across tests would let test order leak state
  between them. `@BeforeAll`/`@AfterAll` (`OrderRepositoryIT`) run once per test
  *class* — used there because starting/stopping the Postgres container per
  test *method* would be correct but needlessly slow; the schema and connection
  are shared, read-only-in-spirit fixtures across that class's few test methods.
- **Assumptions** (`org.junit.jupiter.api.Assumptions`, not used directly in
  this module's files but worth knowing): `assumeTrue(...)`/`assumeFalse(...)`
  abort a test (reported as *skipped*, not failed) if a precondition doesn't
  hold — e.g. skipping a test that needs a specific OS or a specific
  environment variable, rather than failing it. The distinction from a plain
  `if` + early `return` matters for CI reporting: a skipped test is visibly
  flagged as "not run," while a silently-returning test looks like it passed.

### When to use / when not to use
- Use `@ParameterizedTest` whenever you find yourself about to write 3+ nearly
  identical `@Test` methods differing only in input/expected-output values —
  that repetition is exactly the signal to switch.
- Don't over-nest `@Nested` classes — one level (as in this module) keeps
  reports readable; two or three levels deep usually means the test class
  itself is doing too much and could be split into separate top-level classes.
- Use `@BeforeAll` only for fixtures that are genuinely expensive to set up
  *and* safe to share read-only across test methods (a container, a
  compiled-once resource) — never for anything a test method mutates, or you
  reintroduce the shared-mutable-state flakiness `@BeforeEach` exists to avoid.

### Trade-offs & performance implications
- `@BeforeEach` re-running setup for every test method has a real (if usually
  small) per-test cost; for genuinely expensive setup, `@BeforeAll` trades a
  little test isolation for a lot of speed — the trade-off `OrderRepositoryIT`
  makes deliberately (one container for the whole class, not one per test).
- `@Nested` classes each get their own instance per test method by default
  under JUnit 5's default (`PER_METHOD`) lifecycle — so `@BeforeEach` at the
  outer class level still runs before each inner `@Nested` test, keeping the
  isolation guarantee even with nesting.

### Enterprise examples
- Large Java codebases at banks and e-commerce companies commonly use
  `@ParameterizedTest` heavily for validation logic (every locale, every
  currency, every input-boundary combination) precisely because it turns what
  would be hundreds of near-duplicate test methods into a handful of methods
  plus a data table that's easy to extend.

### Common mistakes
- Sharing mutable fixture state across test methods via a `static` field or a
  non-`@BeforeEach`-reset instance field, then being surprised tests pass or
  fail differently depending on run order (JUnit does not guarantee
  declaration order, and parallel execution — not used in this module, but
  common in larger suites — makes shared mutable state actively dangerous, not
  just theoretically risky).
- Forgetting that `@ParameterizedTest` needs at least one argument source
  annotation (`@CsvSource`, `@MethodSource`, etc.) — a bare `@ParameterizedTest`
  with no source fails at test-discovery time, not at compile time, which can
  be a confusing first encounter.

---

## 3. Mockito

### Core APIs
- **`@Mock`**: creates a fully fake implementation of a type — every method
  returns a default value (`null`, `0`, `false`, an empty `Optional`/collection)
  unless explicitly stubbed, and every void method does nothing unless
  explicitly told to throw. `OrderServiceTest` uses `@Mock Inventory inventory`.
- **`@Spy`** (not used in this module, but worth knowing the distinction): wraps
  a *real* object, so unstubbed calls run the real method, and only the calls
  you explicitly stub are overridden. Rarely the right default — reaching for a
  spy is often a sign the design would be cleaner with the real dependency
  broken into a smaller interface you can mock cleanly instead. Use a spy
  narrowly (e.g. verifying a real object's method was called, while still
  letting it run for real) rather than as a general substitute for `@Mock`.
- **`when(...).thenReturn(...)`**: stubs a non-void method's return value for
  specific arguments. Not used in `OrderServiceTest` because every stubbed
  method here (`Inventory.reserve`) is `void` — see `doThrow(...).when(...)`
  below for the void-method equivalent.
- **`doThrow(...).when(mock).method(...)`**: the required form for stubbing a
  *void* method to throw — `when(mock.method(...)).thenThrow(...)` doesn't
  compile for void methods, since `mock.method(...)` itself returns nothing to
  call `when(...)` on. `OrderServiceTest`'s rollback test uses exactly this to
  inject failure on the third `reserve()` call.
- **`verify(mock).method(args)`**: asserts a method was called with matching
  arguments (default: exactly once). `verify(mock, never())...`,
  `verify(mock, times(n))...` adjust the expected call count.
- **`ArgumentCaptor`**: captures the actual argument(s) passed to a mock's
  method for later assertions — useful when you need to assert something about
  an argument's *shape* rather than match it exactly up front (e.g. "some
  `Order` was passed, and its total was positive," without knowing the exact
  `Order` instance ahead of time). `OrderServiceTest` doesn't need one because
  every reserve/release call's exact arguments are known ahead of time, but the
  pattern is: `ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class); verify(mock).save(captor.capture()); assertThat(captor.getValue().total())...`.
- **`InOrder`**: verifies a *sequence* of calls happened in a specific relative
  order across one or more mocks — `OrderServiceTest`'s rollback test uses this
  to pin down that the LIFO rollback releases the most-recently-reserved line
  first (see [diagrams/rollback-sequence.md](diagrams/rollback-sequence.md)).
- **Strict vs. lenient stubbing**: `MockitoExtension` (via `@ExtendWith`) runs in
  **strict stubbing** mode by default — it fails a test if you stub something
  (`when(...)`/`doThrow(...)`) that the test then never actually triggers
  (`UnnecessaryStubbingException`). This is a deliberate guard-rail: an unused
  stub is often a sign the test doesn't exercise the path it thinks it does, or
  is leftover from a previous version of the test that changed underneath it.
  `Mockito.lenient().when(...)` (or `@MockitoSettings(strictness = Strictness.LENIENT)`
  at the class level) opts a specific stub, or a whole class, out of that check —
  reach for it only when you deliberately share one stub across multiple test
  methods with different paths through it, not as a default way to silence the
  warning.

### Why mock `Inventory` in `OrderServiceTest` (not the real class)
This is the single most important design decision in this module's test suite,
covered in full in the Javadoc atop `OrderServiceTest`, summarized here:

1. **Isolation of concerns.** `OrderService.placeOrder()`'s job is
   *orchestration* — reserve, remember, roll back on failure, build the
   `Order`. Whether `Inventory` correctly tracks stock counts is already fully
   covered, separately, by `InventoryTest`. Mocking `Inventory` means a failure
   in `OrderServiceTest` can only be an `OrderService` bug — there's no need to
   first rule out "is this actually an `Inventory` bug leaking through."
2. **Failure injection on demand.** The interesting scenario is "the *third* of
   three `reserve()` calls fails, after the first two already succeeded."
   With the real `Inventory`, forcing that exact scenario means carefully
   pre-seeding stock counts so lines 1–2 have exactly enough and line 3 doesn't
   — fragile, and it obscures *why* line 3 fails behind arithmetic. With a
   mock: `doThrow(...).when(inventory).reserve("SKU-GIZMO", 100)` — direct,
   unambiguous, and doesn't depend on any other stubbed behavior.
3. **Interaction verification.** The property under test is fundamentally about
   *which calls happen, with what arguments, in what order* — not a return
   value. A real `Inventory` has no way to report back "here's the exact
   sequence of reserve/release calls you made on me." `verify()`/`InOrder`
   against a mock give exactly that, which a real object structurally cannot.

### Trade-offs of mocking, more generally
- A mock only enforces the assumptions *you* encoded when you stubbed it — if
  your understanding of `Inventory`'s real contract is wrong (e.g. you assume
  `reserve()` never throws for quantity 0, but the real class does), the mock
  will happily agree with your wrong assumption and the test will pass while
  the real integration is broken. This is exactly why `InventoryTest` (testing
  the real class) and `OrderServiceTest` (mocking it) are both necessary, not
  redundant — one verifies the contract is correct, the other verifies a
  caller uses that contract correctly, and neither alone verifies both.
- Over-mocking (mocking simple, cheap, deterministic collaborators like
  `Customer`/`Product`/`OrderLine`) adds test-authoring overhead and coupling
  to implementation details (you now have to know and stub every method the
  code under test happens to call) for no isolation benefit — those types are
  cheap enough to just construct for real, exactly as `OrderTest` and
  `OrderServiceTest` both do.

### Common mistakes
- Verifying implementation details that aren't part of the actual contract
  (e.g. asserting a private helper method's exact call count) — this makes
  tests brittle to harmless refactors. Verify *behavior* (what the class under
  test is contractually supposed to do to its collaborators), not incidental
  implementation choices.
- Forgetting `doThrow(...).when(mock).voidMethod(...)` is required (not
  `when(mock.voidMethod()).thenThrow(...)`) for void methods — a very common
  first-time Mockito compile error.
- Mocking a type you don't own the contract for loosely (e.g. a JDK collection
  interface) and asserting behavior that doesn't match its real implementation
  — prefer a real instance for anything with well-defined, cheap, standard
  behavior.

---

## 4. AssertJ — why introduce it over plain JUnit assertions

### What it is
A fluent assertion library: `assertThat(x).isEqualTo(y)` instead of JUnit's
`assertEquals(y, x)`. Used throughout this module's test files instead of
JUnit's built-in `Assertions.assertEquals`/`assertTrue`/etc.

### Problem it solves
Plain JUnit assertions read backwards from natural English (`assertEquals(expected, actual)`
— which argument is which is a classic source of confused failure messages when
swapped), have to look up documentation for less common assertion needs
(fields on an exception, membership across a collection), and produce failure
messages that are functional but terse (`expected: <5> but was: <3>`). AssertJ
reads left-to-right as a sentence (`assertThat(stock).isEqualTo(5)` —
unambiguous about which value is "actual"), chains multiple assertions fluently
(`assertThatThrownBy(...).isInstanceOf(X.class).hasMessageContaining(...)`, used
throughout this module's exception tests), and produces much richer,
more specific failure messages (e.g. AssertJ's collection assertions describe
*exactly* which elements were missing/unexpected, not just "collections
differ").

### When to use / when not to use
- Use AssertJ for essentially all assertions in new test code — the ergonomic
  and readability gains apply broadly, not just to complex cases.
- Plain JUnit assertions remain fine (and sometimes marginally simpler) for the
  most trivial boolean checks in a codebase that hasn't already standardized on
  AssertJ — but once one assertion library is in the dependency tree for a
  project, consistency across the suite is more valuable than the marginal
  difference on any single assertion, so this module uses AssertJ everywhere.

### Trade-offs
- One more test-scoped dependency (small — `assertj-core` has no heavyweight
  transitive dependencies). The fluent API has a larger surface to learn than
  JUnit's half-dozen static methods, but nearly all of it is discoverable via
  IDE autocomplete after typing `assertThat(x).`.

### Enterprise examples
- AssertJ (or an equivalent fluent library, e.g. Hamcrest matchers in older
  codebases) is close to a de facto standard in modern enterprise Java test
  suites specifically because of failure-message quality — when a test fails
  in CI at 2am, a precise failure message that pinpoints exactly what differed
  saves real debugging time versus a generic "not equal" message.

### Common mistakes
- Comparing `BigDecimal` with `isEqualTo` when the values might differ only in
  scale (`9.9` vs. `9.90`) — `BigDecimal.equals()` (which `isEqualTo` calls)
  considers scale significant, so this is a common false-failure source. Use
  `isEqualByComparingTo(...)` for monetary/BigDecimal comparisons instead — see
  `OrderTest`/`OrderServiceTest` for this in practice, and
  `java-basics/README.md`'s note on `BigDecimal` for the underlying reason
  `BigDecimal` is used for money at all.

---

## 5. Integration Testing & Testcontainers

### What it is
Testcontainers is a Java library that programmatically starts real
infrastructure (databases, message brokers, and more) in throwaway Docker
containers for the duration of a test run, then tears them down. `@Testcontainers`
+ `@Container` (this module's `OrderRepositoryIT`) wires that lifecycle into
JUnit 5's extension model automatically.

### Why introduced / problem it solves
Testing code that talks to a database has historically meant one of: (a) a
shared, persistent test database everyone's tests fight over (flaky, hard to
reset between runs, a bottleneck for parallel CI), (b) mocking the database
entirely (loses all confidence that your actual SQL works), or (c) an
in-memory database like H2 standing in for production's real engine.
Testcontainers gives every test run its own **real**, **disposable**,
**isolated** instance of the actual production-shape technology, at the cost of
needing Docker available wherever tests run.

### Why Testcontainers over an in-memory database like H2
This is one of the most important practical lessons in this module. H2 can run
in a "PostgreSQL compatibility mode" that mimics *some* of PostgreSQL's SQL
dialect and behavior — but "some" is the operative word. H2's SQL dialect and
constraint/type/function behavior can meaningfully diverge from real
PostgreSQL (or Oracle) in ways that are easy to miss until they bite in
production:
- **Dialect differences**: functions, syntax, and type coercion rules that
  exist in Postgres but not H2 (or vice versa) — a query that compiles and
  passes against H2 can fail outright against real Postgres, or silently
  return different results.
- **Constraint enforcement differences**: whether a given constraint shape
  (foreign keys, check constraints, certain index types) is enforced
  identically. `OrderRepositoryIT`'s
  `foreignKeyConstraintRejectsAnOrderLineForAnUnknownProduct()` test is a
  direct example — passing against H2 would only prove H2 enforces the
  constraint the way *H2* implements the SQL standard, not that PostgreSQL
  (which is what production almost certainly runs) does the same.
- **The net effect**: a test suite that's entirely green against H2 gives
  **false confidence** — it tells you your code works against H2, which is not
  the same claim as "works in production." Testcontainers closes that gap by
  running the literal same database engine and (ideally) the same major
  version as production, in a container that's created and destroyed per test
  run — so a passing test is real evidence, not a proxy.

### When to use / when not to use
- Use Testcontainers for integration tests of anything that talks to real
  infrastructure whose behavior actually matters to correctness — a database,
  a message queue, a cache with eviction semantics you depend on.
- Don't reach for Testcontainers (or any real infrastructure) for a unit test
  of pure logic — `InventoryTest`/`OrderTest`/`OrderStatusTest`/`OrderServiceTest`
  need none of it, and adding it would only slow them down for zero benefit;
  see the Test Pyramid section above.
- Don't run Testcontainers-backed tests on every keystroke/save the way you
  would unit tests — they're meaningfully slower (container start-up) and
  require Docker, which is exactly why this module wires the integration test
  to `mvn verify` (Failsafe) rather than `mvn test` (Surefire) — see "Build &
  run" below.

### Trade-offs & performance implications
- Container start-up cost (typically 1–5 seconds for Postgres, more for
  heavier images) means integration tests are inherently slower than unit
  tests — mitigated at scale by "container reuse" features (starting one
  container and sharing it across an entire test *class*, as `OrderRepositoryIT`
  does with `@BeforeAll`/`@AfterAll`, or Testcontainers' own reuse-across-JVM
  feature for larger suites) rather than one container per test method.
- Requires Docker (or a compatible container runtime) wherever tests run,
  including CI — a real infrastructure dependency that pure unit tests don't
  have. This is precisely why this module keeps `OrderRepositoryIT` on a
  separate Maven lifecycle phase (Failsafe/`verify`) from the unit tests
  (Surefire/`test`) — so `mvn test` works in any environment (including this
  sandbox, which has no Docker), and only `mvn verify` requires it.

### Enterprise examples
- Financial-services backends commonly run their full Testcontainers-backed
  integration suite (Postgres/Oracle, Kafka, Redis) as a required CI gate
  before merge, specifically because a database migration or query that only
  looks correct against H2 has caused real production incidents industry-wide
  — Testcontainers is the direct response to that failure mode.

### Common mistakes
- Assuming "integration test passes" means "will work in production" without
  matching the container's image version to production's actual database
  version — a test against `postgres:16` tells you much less if production
  runs `postgres:13` and relies on version-specific behavior.
- Starting a fresh container per test *method* instead of per test *class* (or
  suite) when the fixture doesn't need method-level isolation — needlessly
  multiplies the seconds-per-container-start-up cost across every test.
- Forgetting Testcontainers requires Docker in CI, and not documenting that
  requirement — a suite that works on every developer's machine but fails
  mysteriously in a CI image with no Docker daemon is a common onboarding
  surprise.

---

## 6. Build & Run

### Why `java-basics` is a source root, not a dependency
`java-basics` (Module 1) predates Maven in this curriculum's build-up — it has
no `pom.xml` of its own, it's compiled and run with plain `javac`/`java` (see
the root [README.md](../README.md)). That means it can't be declared as a
normal `<dependency>` here (there's no artifact for Maven to resolve). Instead,
[pom.xml](pom.xml) uses `build-helper-maven-plugin`'s `add-source` goal to add
`../java-basics/src/main/java` as an **additional source root** of this module:
`mvn` compiles those `.java` files as this module's own main sources (this
module has no `src/main/java` of its own — that added directory *is* its main
source), before compiling and running everything under `src/test/java` against
them.

**The trade-off, explicitly:** the "correct" long-term answer, once more
modules exist, is a real **multi-module Maven reactor** — give `java-basics`
its own minimal `pom.xml`, make it a proper module of a parent POM, and have
`testing/pom.xml` declare a normal `<dependency>` on it (Maven then handles
build ordering and classpath assembly for you, and other modules could depend
on it the same way). The `build-helper-maven-plugin` approach here is a
pragmatic workaround for the repo's *current* state (one Maven module existing
in isolation, everything else built without a build tool at all) — it avoids
retrofitting Maven onto `java-basics` as a side effect of building the Testing
module, which was explicitly out of scope for this module (see the top-level
task scope: only files inside `testing/` may be created or edited). If/when a
parent reactor POM is introduced for the whole repo, this module should switch
to a real `<dependency>` on a `java-basics` artifact and this plugin
configuration should be removed.

### Commands

From `testing/`:

```bash
# Unit tests only (InventoryTest, OrderTest, OrderStatusTest, OrderServiceTest).
# Runs via Surefire, whose default include pattern (**/*Test.java) does NOT match
# OrderRepositoryIT.java -- this command needs no Docker and works in any environment.
mvn test

# Unit tests AND the Testcontainers integration test (OrderRepositoryIT).
# Runs via Failsafe (integration-test + verify phases), whose default include
# pattern (**/*IT.java) is exactly why that file is named ...IT, not ...Test.
# REQUIRES Docker (or a compatible container runtime) to be running locally.
mvn verify
```

Both commands were written and reviewed carefully but **could not be executed
in this sandbox** — there is no JDK, Maven, or Docker installed here. Run them
on a machine with JDK 21+, Maven 3.9+, and (for `mvn verify`) Docker installed
and running.
