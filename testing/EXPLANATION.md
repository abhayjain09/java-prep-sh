# Module 10 — Line-by-Line Explanation

Walks through every file in this module in the order you should read them:
`pom.xml` first (the build wiring), then the unit test classes (simplest
collaborators first), then the Mockito-based `OrderServiceTest`, then the
Testcontainers-based `OrderRepositoryIT`. The "why" for each design choice is
also inline in the code's own comments — this file adds narrative and connects
choices across files.

## `pom.xml`

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-bom</artifactId>
    <version>${testcontainers.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```
A **BOM (Bill of Materials)** import inside `<dependencyManagement>`: rather
than pinning a version on every individual `org.testcontainers:*` artifact
(`testcontainers`, `junit-jupiter`, `postgresql`), importing the BOM once means
Maven resolves all of them to versions the Testcontainers project itself
verified work together. This is the standard pattern for multi-artifact
libraries (Spring Boot's own BOM works the same way) — it prevents the subtle
bugs that come from accidentally mixing incompatible versions of a library's
own sub-modules.

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>build-helper-maven-plugin</artifactId>
    ...
    <goals><goal>add-source</goal></goals>
    <configuration>
        <sources>
            <source>${project.basedir}/../java-basics/src/main/java</source>
        </sources>
    </configuration>
</plugin>
```
Bound to the `generate-sources` phase (runs before `compile`), this adds
`java-basics`'s source directory as an extra root this module compiles as its
own main code. See README.md's "Why `java-basics` is a source root, not a
dependency" for the full trade-off discussion versus a proper multi-module
reactor.

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
</plugin>
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <executions>
        <execution>
            <goals><goal>integration-test</goal><goal>verify</goal></goals>
        </execution>
    </executions>
</plugin>
```
Two different Maven plugins, bound to two different lifecycle phases, is what
splits "fast unit tests, no Docker needed" (`mvn test` → Surefire) from "also
run the Testcontainers integration test" (`mvn verify` → Failsafe). Each
plugin's *default* include pattern (Surefire: `**/*Test.java`; Failsafe:
`**/*IT.java`) does the actual routing — no custom include/exclude
configuration was needed because the test class names already follow each
plugin's convention (`InventoryTest`, `OrderTest`, `OrderStatusTest`,
`OrderServiceTest` vs. `OrderRepositoryIT`).

## `domain/InventoryTest.java`

```java
@BeforeEach
void setUp() {
    inventory = new Inventory();
}
```
A fresh `Inventory` before every test method. `Inventory` is mutable
(`restock`/`reserve`/`release` all change its internal map), so this is what
guarantees every test starts from a known, empty state regardless of what ran
before it or in what order — see the class Javadoc for the flakiness this
prevents.

```java
@Nested
@DisplayName("reserve()")
class ReserveTests { ... }
```
Groups the three `reserve()`-focused tests (success, insufficient stock,
boundary) under one named heading. JUnit 5 instantiates `InventoryTest` fresh
for each `@Nested` test method too, so the outer class's `@BeforeEach` still
runs before each one — nesting doesn't weaken the fresh-fixture guarantee.

```java
assertThatThrownBy(() -> inventory.reserve("SKU-WIDGET", 5))
        .isInstanceOf(InsufficientStockException.class)
        .satisfies(thrown -> {
            InsufficientStockException ex = (InsufficientStockException) thrown;
            assertThat(ex.sku()).isEqualTo("SKU-WIDGET");
            ...
        });
```
`.satisfies(...)` lets you run arbitrary further assertions on the caught
exception within the same fluent chain — used here instead of parsing
`getMessage()` because `InsufficientStockException` exposes `sku()`/
`requested()`/`available()` as real accessors specifically so callers (and
tests) never need to.

```java
inventory.restock("SKU-WIDGET", 5);
inventory.reserve("SKU-WIDGET", 5);
assertThat(inventory.stockOf("SKU-WIDGET")).isZero();
```
The boundary test: `available == requested` is the single point where
`Inventory.reserve()`'s `if (available < quantity)` check could most easily be
off by one (e.g. a typo'd `<=`). Testing exactly this value, not just "well
above" and "well below," is what would actually catch that class of bug.

## `domain/OrderTest.java`

```java
assertThat(order.totalAmount()).isEqualByComparingTo("39.97");
```
`isEqualByComparingTo` (not `isEqualTo`) throughout this file and
`OrderServiceTest`: `BigDecimal.equals()` treats `9.97` and `9.970` as
*unequal* because it compares scale as well as value — a classic `BigDecimal`
trap flagged in `java-basics/README.md`. `isEqualByComparingTo` delegates to
`compareTo()`, which is scale-independent, matching how you actually want to
compare monetary amounts in a test.

```java
List<OrderLine> lines = order.getLines();
assertThatThrownBy(() -> lines.add(new OrderLine(gadget, 1)))
        .isInstanceOf(UnsupportedOperationException.class);
assertThat(order.getLines()).hasSize(1);
```
Two assertions doing two different jobs: the first proves `getLines()`
returns something genuinely immutable (not just "a copy you're not supposed to
mutate," but one that *cannot* be mutated). The second proves the failed
mutation attempt had no side effect on `Order`'s real internal state — calling
`order.getLines()` again afterward still reports exactly the one line that was
actually added via `addLine()`.

```java
assertThatThrownBy(() -> order.transitionTo(OrderStatus.DELIVERED))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ORD-1")
        ...
assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
```
Two halves of one contract: `transitionTo()` must reject the illegal
transition (first assertion) *and* must not have mutated `status` before
rejecting it (second assertion) — a buggy implementation that set `status`
first and validated second would fail only the second half, which is exactly
why both are asserted rather than just the exception.

## `domain/OrderStatusTest.java`

```java
@ParameterizedTest(name = "{0} -> {1} should be legal = {2}")
@CsvSource({ ... 25 rows ... })
void canTransitionToMatchesTheExpectedLegalityForEveryPair(
        OrderStatus from, OrderStatus to, boolean expectedLegal) {
    assertThat(from.canTransitionTo(to)).isEqualTo(expectedLegal);
}
```
One test *method*, 25 test *executions* (one per `@CsvSource` row) — each
shows up individually in test reports thanks to the `name = "..."` template
using `{0}`/`{1}`/`{2}` placeholders for that row's arguments. JUnit 5's
built-in implicit converters turn each CSV string column into the parameter's
declared type automatically: `"PENDING"` → `OrderStatus.PENDING` (enum
conversion), `"true"`/`"false"` → `boolean` — no manual parsing code needed.
The table is written independently of `OrderStatus.legalNextStates()`'s
implementation (not derived from it) specifically so it can catch a
regression in that method, not just echo it back.

## `service/OrderServiceTest.java`

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {
    @Mock
    private Inventory inventory;
    @InjectMocks
    private OrderService orderService;
```
`MockitoExtension` (from `mockito-junit-jupiter`) is what makes `@Mock`/
`@InjectMocks` work at all under JUnit 5 — it initializes every `@Mock` field
before each test and validates stubbing usage afterward (see "strict
stubbing" below). `@InjectMocks` inspects `OrderService`'s only constructor
(`OrderService(Inventory)`) and passes the `@Mock Inventory` into it — the
same result as writing `orderService = new OrderService(inventory);` by hand
in a `@BeforeEach`, just declarative.

```java
Order order = orderService.placeOrder(customer, lines);
...
verify(inventory).reserve("SKU-WIDGET", 2);
verify(inventory).reserve("SKU-GADGET", 1);
verify(inventory, never()).release(anyString(), anyInt());
```
No `when(...)`/`doThrow(...)` stubbing at all in the happy-path test — because
`Inventory.reserve()` is `void`, Mockito's default behavior for an unstubbed
void call on a mock is simply "do nothing and return," which *is* "there was
enough stock" from `OrderService`'s point of view. The `verify()` calls then
check the actual payoff of mocking: that `OrderService` called `reserve()`
with exactly the right arguments, something a real `Inventory` object has no
way to report back after the fact.

```java
doThrow(new InsufficientStockException("SKU-GIZMO", 100, 0))
        .when(inventory).reserve("SKU-GIZMO", 100);
```
The failure-injection line. `doThrow(...).when(mock).method(...)` is required
(rather than `when(mock.method(...)).thenThrow(...)`) specifically because
`reserve()` is `void` — there's no return value for `when(...)` to be called
on. This one line replaces what would otherwise require pre-seeding exact
stock quantities on a real `Inventory` to force this exact line, this exact
call, to fail.

```java
verify(inventory).release("SKU-WIDGET", 2);
verify(inventory).release("SKU-GADGET", 3);
verify(inventory, never()).release("SKU-GIZMO", 100);
```
The core rollback assertion, in three parts: the two lines that *actually*
reserved stock before the failure must be released (undoing the reservation),
and the line that failed must specifically **not** be released — since its
`reserve()` call never mutated any state (it threw first), releasing it would
incorrectly credit stock that was never taken.

```java
InOrder rollbackOrder = inOrder(inventory);
rollbackOrder.verify(inventory).release("SKU-GADGET", 3);
rollbackOrder.verify(inventory).release("SKU-WIDGET", 2);
```
`OrderService.placeOrder()` tracks successful reservations in an `ArrayDeque`
used as a stack (`push()` on success). Its catch block then iterates that
deque front-to-back, which — because `push()` inserts at the front — visits
the *most recently* reserved line first. This `InOrder` check pins that exact
LIFO sequence down explicitly; see
[diagrams/rollback-sequence.md](diagrams/rollback-sequence.md) for the visual
trace of the whole scenario.

```java
verifyNoMoreInteractions(inventory);
```
Placed last, after every expected interaction has already been individually
verified above — this closes the loop by asserting *nothing else* happened to
the mock (e.g. no accidental `restock()` call). Note this method must come
after all the specific `verify()` calls it's meant to complement, not before —
Mockito considers a call "accounted for" once any `verify()` targeting it has
run.

```java
// no stubbing of inventory at all in this @Nested class
BigDecimal imperativeTotal = orderService.totalSpentByImperative(customer, orders);
BigDecimal streamsTotal = orderService.totalSpentByStreams(customer, orders);
assertThat(imperativeTotal).isEqualByComparingTo(streamsTotal);
```
A **property-style check**: rather than asserting each method individually
against a hand-computed expected value only, the two implementations are
asserted equal *to each other* first — proving the loop-based and
stream-based versions of the same computation genuinely agree, which is the
actual point `java-basics` keeps both side by side to demonstrate. The
`@Mock Inventory` field goes completely unused in this test, which is fine:
Mockito's strict-stubbing mode flags stubs that go *unused*, not mocks that
receive no stubbing at all.

## `integration/OrderRepositoryIT.java`

```java
@Testcontainers
class OrderRepositoryIT {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")...
```
`@Testcontainers` is the JUnit 5 extension; `@Container` on a `static` field
tells it to start this container once, before any `@Test` in the class runs,
and stop it once, after the last one finishes (class-scoped lifecycle — if the
field were non-`static`, it would restart per test method instead). Pinning
`postgres:16-alpine` fixes both the major version (reproducible behavior
across machines/CI) and chooses the smaller Alpine base image.

```java
connection = DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
```
`getJdbcUrl()` reflects whatever host port Docker actually assigned the
container (never a fixed, guessable port — important for running tests in
parallel or in shared CI infrastructure without port collisions).

```java
schema.execute("""
        CREATE TABLE customers ( ... )
        """);
```
A hand-written, minimal schema — explicitly not Flyway/Liquibase and not
Module 5's real JPA entity mappings, both because a real migration tool is out
of scope for demonstrating Testcontainers mechanics, and because `database/`
and `spring/` (the modules that would own that schema) may not exist yet. Java
21 text blocks (`"""`) make multi-line SQL readable without string
concatenation or escaped newlines.

```java
BigDecimal total = rs.getBigDecimal("total");
assertThat(total).isEqualByComparingTo("39.97");
```
Cross-checks a real SQL `JOIN` + `SUM` against the same arithmetic
`java-basics`' `Order.totalAmount()` would compute for equivalent in-memory
objects — the point being to prove the *query* is correct against a real
engine, not just that Java's own `BigDecimal` math is correct (already proven
by `OrderTest`).

```java
assertThatThrownBy(() -> {
    try (Statement stmt = connection.createStatement()) {
        stmt.execute("INSERT INTO order_lines ... VALUES ('ORD-1', 'SKU-DOES-NOT-EXIST', 1)");
    }
}).isInstanceOf(SQLException.class);
```
Proves the foreign key constraint (`REFERENCES products(sku)`) is actually
enforced by asking the real engine to violate it and asserting it refuses —
this is precisely the kind of assertion that only means something when it's
running against the real database engine (see README's H2-vs-Testcontainers
discussion for why an in-memory substitute would weaken this test's meaning).
