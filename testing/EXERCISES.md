# Module 10 — Exercises

Do these in order — later ones build on earlier ones' code and understanding.
Work inside `testing/src/test/java/com/interviewprep/orders/`. You'll need a
JDK 21+ and Maven 3.9+ locally to actually run anything here (this sandbox has
neither) — after each exercise, run `mvn test` (or `mvn verify` for exercises
touching the Testcontainers test) and confirm your new/changed tests actually
pass, not just compile.

## 1. (Beginner) Add a boundary test for `Inventory.restock()`

`InventoryTest` covers `reserve()`'s boundary case (reserving exactly the
available amount) but not an equivalent edge for `restock()`/`release()`:
what happens when `restock()` or `release()` is called with a quantity of
`0` or a negative number? Read `Inventory.requirePositive()` in
`java-basics/src/main/java/com/interviewprep/orders/domain/Inventory.java`
to see what it's actually supposed to do, then add tests to `InventoryTest`
(as a new `@Nested` group, e.g. `ValidationTests`) proving `restock(sku, 0)`
and `reserve(sku, -1)` both throw `IllegalArgumentException`.

**Check yourself:** should `InsufficientStockException` and
`IllegalArgumentException` ever be confused by a caller catching one but not
the other? Why does `Inventory` use two different exception types here rather
than one generic one?

## 2. (Beginner) Add a `@CsvSource` case that would catch a specific bug

`OrderStatusTest`'s 25-row table is written independently of
`OrderStatus.legalNextStates()`. Prove that independence matters: temporarily
introduce a bug in a **local copy** of `OrderStatus` (don't edit the real
`java-basics` file — copy just the enum into a throwaway scratch file, or
comment through the change mentally) — e.g. change `case SHIPPED -> Set.of(DELIVERED);`
to `case SHIPPED -> Set.of(DELIVERED, CANCELLED);` — and identify exactly
which row(s) of the existing `@CsvSource` table would fail. Then write one
sentence explaining why a test suite that *derived* its expected values from
the same `legalNextStates()` method (instead of hand-writing them) would have
missed this bug entirely.

## 3. (Intermediate) Use an `ArgumentCaptor` in `OrderServiceTest`

`OrderServiceTest`'s happy-path test currently verifies `reserve()` calls with
exact literal arguments (`verify(inventory).reserve("SKU-WIDGET", 2)`). That
works because the arguments are primitives/Strings you already know ahead of
time. Note that the `Order` object `placeOrder()` builds and returns isn't a
mock argument at all (it's a return value you already hold a reference to) —
`ArgumentCaptor` is for the opposite situation: an object *passed into* a
mock that you don't have a reference to ahead of time. To exercise that: add
a **new method** to `OrderService` (in your own scratch copy, or as a thought
exercise) that takes the built `Order` and passes it to some hypothetical
`inventory.audit(Order order)` method, then write a test using
`ArgumentCaptor<Order>` to capture what was passed to `audit(...)` and assert
on its `totalAmount()`. This exercises the pattern for when you need to
assert on an argument's *shape*, not its exact identity — useful once mocked
collaborators take richer objects than primitives/Strings.

**Check yourself:** why can't you just use `verify(inventory).audit(theExactOrderInstance)`
here the way the existing tests do for `reserve("SKU-WIDGET", 2)`? What's
different about comparing a mutable object you don't have a reference to yet
vs. comparing literal `String`/`int` values you specified yourself?

## 4. (Intermediate) Make the rollback test fail on purpose, then fix it

In a scratch copy of `OrderService.placeOrder()` (do not edit the real
`java-basics` file), change the catch block's rollback loop to iterate
`requestedLines` (a `List`, in original order) instead of `reserved` (the
`ArrayDeque` stack) — a plausible-looking but subtly wrong refactor. Run
`OrderServiceTest`'s `PlaceOrderRollback` test against your modified copy
(you'll need to point the test's import/classpath at your scratch version, or
just reason through it by hand) and identify exactly which assertion fails
first, and why. Then explain in a comment: what real production bug would
this refactor introduce that the passing-before-your-change,
failing-after-your-change test catches?

## 5. (Senior) Design a test for a hypothetical `Inventory.reserveAll()`

`java-basics/EXERCISES.md` (Module 1, exercise 3) asks you to implement
`Inventory.reserveAll(Map<String, Integer> quantitiesBySku)` — an
all-or-nothing multi-SKU reservation built into `Inventory` itself, rather
than `OrderService` doing it by hand with a `Deque`. Assume that method now
exists. Design (write out, don't necessarily implement against real code) the
test plan for it: which parts belong in a unit test against the real
`Inventory` (no mocking — `Inventory` has no collaborators to mock), and is
there anything left for `OrderServiceTest` to test at all once `Inventory`
itself guarantees atomicity? Write down what changes (if anything) in
`OrderServiceTest`'s rollback test once `placeOrder()` can delegate to
`reserveAll()` instead of looping and rolling back manually — does the
Mockito-based rollback test in this module become simpler, unnecessary, or
unchanged?

## 6. (Scenario) A teammate wants to delete `OrderRepositoryIT` because "it's slow and needs Docker"

A teammate on your team proposes deleting `OrderRepositoryIT` from the suite
entirely, arguing: "it's the only test in this module that needs Docker, it's
slower than everything else combined, and we already have thorough unit test
coverage on `Inventory`/`Order`/`OrderService` — what does this test actually
buy us that's worth the CI complexity?" Write the response you'd give in code
review. Your answer should address: (a) what specific class of bug this test
can catch that no amount of additional unit testing against mocked/real
in-memory objects could ever catch, citing the specific
`foreignKeyConstraintRejectsAnOrderLineForAnUnknownProduct()` test as a
concrete example; (b) whether "it's slow" is actually a good argument for
deleting it outright versus a good argument for *where in the pipeline* it
should run (every commit? only pre-merge? only nightly?); and (c) what you'd
say if the teammate offered "let's just switch it to H2" as a compromise —
tie your answer back to the README's Testcontainers-vs-H2 discussion, but
argue it in your own words as if defending it live in a review thread.
