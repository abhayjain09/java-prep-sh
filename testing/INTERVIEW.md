# Module 10 — Interview Questions

Organized by topic, then by level (beginner → intermediate → senior →
scenario). Each includes an ideal answer outline and likely follow-ups.
Testing questions show up everywhere — as a dedicated round at process-heavy
shops like **S&P Global**, **JPMorgan**, and **Goldman Sachs** (where
correctness and auditability matter enormously and "how do you know this
works" is asked directly), and woven into system-design and coding rounds at
**Amazon**, **Microsoft**, **Google**, and **Atlassian** (where "how would you
test this" is a near-guaranteed follow-up to any design or coding question).

---

## JUnit

**Beginner:** "What's the difference between `@BeforeEach` and `@BeforeAll`?"
*Ideal answer:* `@BeforeEach` runs before every test method — used for fresh,
isolated fixtures (see `InventoryTest`'s `setUp()`, which builds a brand-new
`Inventory` per test so tests can't leak mutable state into each other).
`@BeforeAll` runs once before all test methods in the class, on a `static`
method — used for expensive, shareable, effectively-read-only setup (see
`OrderRepositoryIT`, which starts one Postgres container and opens one
connection for the whole class rather than per test).
*Follow-up:* "What would go wrong if you used `@BeforeAll` for `InventoryTest`'s
setup instead?" → All test methods would share one `Inventory` instance;
since `Inventory` is mutable, one test's `reserve()` calls would leak stock
changes into the next test, making results depend on execution order — exactly
the flakiness `@BeforeEach` exists to prevent for mutable fixtures.

**Intermediate:** "What is `@ParameterizedTest` for, and when would you reach for it instead of writing multiple `@Test` methods?"
*Ideal answer:* Runs the same test logic once per row of supplied data
(`@CsvSource`, `@MethodSource`, `@EnumSource`, etc.) — see `OrderStatusTest`,
which tests all 25 `OrderStatus` transition pairs as one parameterized method
instead of 25 near-identical `@Test` methods. Reach for it whenever you notice
several `@Test` methods that differ only in input/expected-output values —
that repetition is the signal.
*Follow-up:* "How does JUnit know to convert `"PENDING"` in a CSV string into
an `OrderStatus.PENDING` enum constant?" → JUnit 5 ships built-in implicit
converters for common target types (enums, primitives, `String`) used by
`@CsvSource`/`@ValueSource` parameters — no manual parsing code is needed for
these common cases; a custom `ArgumentConverter` would be needed for a more
exotic target type.

**Senior:** "Your team's test suite has grown a habit of sharing mutable `static` fixtures across test classes to save setup time. What's your concern, and how do you fix it without losing the speed benefit entirely?"
*Ideal answer:* Shared mutable `static` state across tests reintroduces
order-dependence and breaks parallel test execution (a feature many CI setups
rely on for speed) — a test that passes in isolation can fail depending on
what ran before it, which is exactly the flakiness class `@BeforeEach`-per-test
fixtures avoid. The fix that keeps most of the speed benefit: identify what's
actually *expensive* to set up (a container, a compiled resource) versus what
just happens to be convenient to share (mutable domain objects), and use
`@BeforeAll` only for the former — genuinely read-only or immutable shared
state — while keeping per-test `@BeforeEach` fixtures for anything a test
method might mutate. `OrderRepositoryIT` in this module draws exactly that
line: the Postgres container and connection are `@BeforeAll` (expensive,
effectively read-only infrastructure), while every domain unit test in this
module uses `@BeforeEach` (cheap, and each test does mutate its fixture).

**Scenario:** "A junior engineer's PR adds a `@Test` method with no assertions at all — just calls to the method under test and a `System.out.println` of the result, 'to make sure it doesn't throw.' How do you respond?"
*Ideal answer:* Point out that a test with no assertion isn't really testing
anything beyond "didn't throw an unexpected exception," which is a real but
very weak property — it will pass even if the method returns a completely
wrong value. Ask what the actual expected behavior is, and turn it into a real
assertion (`assertThat(result).isEqualTo(expected)`), or, if "doesn't throw"
genuinely is the whole point (e.g. testing that valid input doesn't trigger
validation), make that explicit with `assertThatCode(...).doesNotThrowAnyException()`
rather than a bare method call with no assertion at all, so the intent is
visible to future readers and the test actually fails loudly if that
assumption breaks.

---

## Mockito

**Beginner:** "What's the difference between a mock and a real object in a test?"
*Ideal answer:* A mock (`@Mock`) is a fake implementation Mockito generates at
runtime — every method returns a default value (or does nothing, for void
methods) unless you explicitly stub it, and it records every call made to it
so you can `verify()` afterward. A real object runs its actual logic. Use a
mock when you want to isolate the class under test from a collaborator's real
behavior (or when the real behavior is expensive/hard to trigger); use a real
object when the collaborator is cheap, deterministic, and its correctness
isn't what you're testing right now — see `OrderTest`, which uses real
`Customer`/`Product`/`OrderLine` objects throughout, versus `OrderServiceTest`,
which mocks `Inventory` specifically.

**Intermediate:** "Why does `OrderServiceTest` mock `Inventory` instead of using the real class from `java-basics`?"
*Ideal answer:* Three reasons, in order of importance: (1) isolation —
`OrderService`'s job under test is orchestration (reserve, track, roll back),
which is a separate concern from whether `Inventory` itself correctly tracks
stock (already covered by `InventoryTest`) — mocking means a test failure here
can only be an `OrderService` bug; (2) failure injection — forcing "the third
of three reserve calls fails" is a one-line `doThrow(...)` against a mock,
versus fragile stock-count pre-seeding against a real `Inventory`; (3)
interaction verification — the property under test (which calls happened,
with what arguments, in what order) is something only a mock can report back
directly via `verify()`/`InOrder`.
*Follow-up:* "Is there any downside to this approach?" → A mock only enforces
the assumptions you encoded when stubbing it — if your understanding of
`Inventory`'s real contract is subtly wrong, the mock will agree with the
wrong assumption and this test won't catch the mismatch. That's why
`InventoryTest` (testing the real contract) and `OrderServiceTest` (testing a
caller's correct use of that contract) are both necessary — one alone doesn't
cover what the other does.

**Senior:** "What is 'strict stubbing,' and why might a test fail even though every assertion in it passed?"
*Ideal answer:* `MockitoExtension`'s default strictness mode
(`Strictness.STRICT_STUBS`) fails a test if a stub was configured
(`when(...)`/`doThrow(...)`) but never actually triggered during that test's
execution — reported as `UnnecessaryStubbingException`, separately from any
assertion failure. This can happen even with all assertions passing, e.g. if
a test stubs a call the code path under test doesn't end up making (often a
sign the test doesn't exercise what it thinks it does, or is leftover from an
earlier version of the test). The fix is either to remove the unused stub, or
— if genuinely intentional (e.g. one stub shared defensively across multiple
paths in a `@BeforeEach` that not every test method reaches) — mark it
`lenient()` explicitly, which documents that the looseness is deliberate
rather than accidental.

**Scenario:** "You're reviewing a PR where every single method call on a mock — including calls to simple getters — is wrapped in a `verify()`. What's your feedback?"
*Ideal answer:* Over-verification couples the test tightly to *how* the code
under test happens to be implemented rather than *what* it's contractually
responsible for — verifying every incidental call (including simple getters
that have no side effects worth confirming) makes the test brittle to
harmless refactors that don't change behavior at all. Recommend focusing
`verify()` calls on interactions that represent real contractual behavior
(state-changing calls like `reserve()`/`release()` in `OrderServiceTest`,
which genuinely matter to the property under test) and letting side-effect-free
reads happen without a corresponding `verify()`, unless that specific call
count is itself part of what's being tested (e.g. proving a value is cached
and not re-fetched).

---

## Test Design & the Test Pyramid

**Beginner:** "What's the difference between a unit test and an integration test?"
*Ideal answer:* A unit test exercises one class in isolation from its
collaborators (real, cheap ones used directly — `OrderTest`; or expensive/hard
ones mocked out — `OrderServiceTest` mocking `Inventory`), runs in
milliseconds, with no real I/O. An integration test exercises a real external
boundary — a database, a queue, another service — for real, to verify an
assumption about how that boundary actually behaves (`OrderRepositoryIT`
against real PostgreSQL). Integration tests are slower and need real
infrastructure (Docker, in this module's case) available wherever they run.

**Intermediate:** "Why is the test pyramid shaped like a pyramid — why not equal numbers of unit, integration, and end-to-end tests?"
*Ideal answer:* Cost and specificity both scale up the pyramid: unit tests are
cheap (milliseconds) and pinpoint exactly which class/branch is wrong on
failure; integration tests cost real seconds (container start-up, network
round-trips) and tell you a boundary assumption is wrong; E2E tests cost the
most (seconds to minutes, often flaky) and tell you the least precisely
*where* a wired-together system broke. Optimizing for both fast feedback and
real confidence at the boundaries that matter means: many of the cheap,
precise tests, some of the moderately expensive boundary tests, and few of the
expensive, imprecise, whole-system tests. Equal numbers at every tier would
mean either a suite that's needlessly slow (if unit-test volume were capped to
match E2E volume) or one that's a maintenance nightmare (if E2E volume matched
unit-test volume).
*Follow-up:* "What's the 'ice cream cone' anti-pattern?" → The pyramid
inverted — mostly E2E/manual tests, few unit tests — common in teams that
added testing late or test-after rather than test-alongside; it produces a
slow, flaky suite that's expensive to maintain and imprecise about failure
location, which often leads teams to stop trusting (and eventually stop
running) their own tests.

**Senior:** "How do you decide where a specific new test belongs — unit, integration, or E2E?"
*Ideal answer:* Ask what you're actually uncertain about. If it's "is this
business logic correct" (a calculation, a state transition, a validation
rule) — unit test, mocking only collaborators that are expensive/hard to
trigger realistically (`OrderServiceTest`'s failure-injection case is the
clearest example: real `Inventory` can't easily be forced into that exact
failure shape). If it's "does my code's assumption about a real external
system hold" (a specific SQL query against a specific engine, a specific
message format against a specific broker) — integration test, and only enough
of them to cover the realistic query/constraint shapes, not exhaustive
coverage of business logic that a unit test already covers more cheaply. If
it's "does this whole user-facing flow work end-to-end" — E2E, reserved for
the handful of flows that most matter to the business, not a substitute for
either of the other two tiers.

**Scenario:** "Your integration test suite (Testcontainers-backed) takes 8 minutes and is starting to slow down every PR. How do you speed it up without losing confidence?"
*Ideal answer:* First check for the common, low-risk wins: are containers
being started per test *method* when per test *class* (or per suite, via
Testcontainers' reuse feature) would be safe — `OrderRepositoryIT`'s
`@BeforeAll`/`@AfterAll` container lifecycle is exactly this optimization
applied at the class level. Second, check whether some "integration" tests
are actually testing pure logic that happens to run against a real database
unnecessarily — those should move down to the unit tier, mocking the
repository/DAO layer instead. Only after those are exhausted consider
splitting the suite across pipeline stages (fast unit tests on every push,
integration tests pre-merge or in a parallel CI stage) rather than cutting
integration coverage outright — the goal is faster feedback, not less
confidence.

---

## Testcontainers

**Beginner:** "What problem does Testcontainers solve?"
*Ideal answer:* Gives each test run its own real, disposable instance of
actual infrastructure (a database, in this module's case — real PostgreSQL,
not an in-memory substitute) via Docker, instead of either mocking the
database entirely (loses confidence your SQL actually works) or relying on a
shared, persistent test database (flaky, contended, hard to reset between
runs).

**Intermediate:** "Why did this module use Testcontainers with real PostgreSQL instead of an in-memory database like H2?"
*Ideal answer:* H2's "PostgreSQL compatibility mode" mimics *some* of
PostgreSQL's SQL dialect and behavior, but dialect quirks, function support,
type coercion, and constraint enforcement can all meaningfully diverge from
the real engine. A test passing against H2 only proves the code works against
H2's implementation of the SQL standard — not against what's actually running
in production. `OrderRepositoryIT`'s foreign-key-constraint test is a concrete
example: it's only meaningful evidence about production behavior because it
runs against the literal same database engine (and, ideally, version)
production uses.
*Follow-up:* "Is H2 ever the right choice?" → For very fast local
smoke-testing during early development, or for genuinely pure-SQL-standard
logic with no engine-specific behavior involved, H2 can be a reasonable
speed/confidence trade-off — but it shouldn't be the thing your CI pipeline's
confidence in database-facing code ultimately rests on, and any team relying
on it that way should know exactly what dialect risk they're accepting.

**Senior:** "How do you keep a growing Testcontainers-backed integration suite fast, and how do you handle a database version mismatch between test and production?"
*Ideal answer:* Speed: minimize container start-up overhead by scoping
containers to the class or suite rather than per test method (as
`OrderRepositoryIT` does), and consider Testcontainers' cross-JVM container
reuse feature for larger suites where even per-class start-up adds up.
Version mismatch: pin the container image tag to the actual production
database's major version (and ideally minor version) explicitly rather than
using `latest` or an arbitrary tag — a test passing against `postgres:16` when
production runs `postgres:13` can miss version-specific behavior differences,
which defeats much of the point of using a real engine at all. Treat the pinned
version as something that gets updated deliberately (and re-tested) alongside
any real production database upgrade, not left to drift.

**Scenario:** "A teammate wants to delete the one Testcontainers-based integration test in this module because it's the only one requiring Docker and it's noticeably slower than the rest of the suite. How do you respond?"
*Ideal answer:* Acknowledge the real cost (Docker dependency, seconds of
container start-up) but distinguish it from a reason to *delete* it versus a
reason to *place it correctly* in the pipeline: run it on `mvn verify`
(pre-merge or CI-gate) rather than on every `mvn test` (fast local loop) — the
actual split this module implements via Surefire vs. Failsafe. Then name the
specific class of bug it uniquely catches: whether a real constraint
(`REFERENCES products(sku)` in `OrderRepositoryIT`) is actually enforced by
the real engine the way the code assumes — no amount of additional mocked or
in-memory testing proves that, because the whole point is verifying an
assumption about infrastructure the code doesn't control. If the compromise
offered is "switch it to H2 instead of deleting it," explain that this trades
away exactly the property that made the test meaningful (see the
Testcontainers-vs-H2 question above) in exchange for speed that's better
recovered by fixing pipeline placement or container-reuse, not by testing
against a different, non-production database engine.
