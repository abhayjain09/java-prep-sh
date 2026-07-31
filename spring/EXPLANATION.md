# Module 5/8 — Line-by-Line Explanation

Walks through every file in [src/main/java/com/interviewprep/orders/springapp](src/main/java/com/interviewprep/orders/springapp)
in dependency order (entities first, then repositories, then DTOs/exceptions,
then services, then controllers, then config). Most of the "why" already
lives in each file's own Javadoc — this document adds narrative connecting
choices ACROSS files, plus a few topics too broad for any single file's
comment block.

## `entity/OrderStatus.java`

Same enum-with-behavior pattern as java-basics — see
`README.md`'s design note (section 0) for why this is a fresh copy rather
than an import. One subtlety worth calling out: this enum has NO JPA
annotations at all. Persistence is declared on the *field* that uses it
(`Order.status`, via `@Enumerated(EnumType.STRING)`), not on the enum type
itself — the enum stays a plain, persistence-ignorant Java type, which is
exactly what lets it also be used, unchanged, as the type of
`OrderStatusUpdateRequest.status` (a DTO) and a `@RequestParam` in
`OrderController.list` (Spring's built-in `Converter<String, Enum>`
deserializes `?status=PENDING` into this enum automatically, no extra code).

**Why `EnumType.STRING` and not the default `EnumType.ORDINAL`:** `ORDINAL`
stores the enum's *position* (0, 1, 2...) as an integer. If a new status
were ever inserted in the middle of the enum's declaration order (not
appended at the end), every already-stored ordinal would now silently refer
to the WRONG status — a serious, silent data-corruption bug. `STRING`
stores the constant's name (`"PENDING"`) — reordering is safe, and the
stored value is human-readable directly in the database (useful when
debugging with a raw SQL client). The only downside is slightly more
storage and a marginally slower string comparison versus an integer one —
completely negligible next to the correctness risk `ORDINAL` carries.

## `entity/Customer.java`

Read this file's own Javadoc first — it carries the module's most detailed
explanation (records-vs-entities, surrogate-vs-natural keys, equals/hashCode
on a natural key) and every other entity's Javadoc refers back to it rather
than repeating the reasoning.

One thing worth adding here: the `orders` field is annotated
`@OneToMany(mappedBy = "customer", ...)` with NO `cascade` or
`orphanRemoval`. Contrast this with `Order.lines`, which DOES cascade.
The rule of thumb: cascade should mirror the real-world lifecycle
dependency. An `OrderLine` has no meaning without its `Order` — deleting
the order should delete its lines. A `Customer`'s `Order` history should
almost never be cascade-deleted alongside the customer (audit/financial
retention requirements usually forbid it) — deleting a `Customer` in this
module, as written, would fail with a foreign-key constraint violation if
they have existing orders, which is the SAFE failure mode (forces an
explicit decision — anonymize? archive? block deletion entirely? — rather
than silently destroying order history).

## `entity/Product.java`

The Inventory-as-field decision lives here — see this file's Javadoc and
`README.md` section 6 for the full write-up. Two additional details worth
flagging:

- `decrementStock`/`restock` are **entity methods**, not something done via
  direct field mutation from the service layer (`product.setStockQuantity
  (x)` doesn't even exist — there's no such setter). This mirrors
  java-basics' `Inventory` encapsulation lesson exactly: the invariant
  ("stock never negative") is enforced in ONE place regardless of how many
  service methods eventually call it, rather than trusting every caller to
  remember to check first.
- This is technically a step toward a **"rich domain model"** (entities
  that carry behavior, not just data) versus an **"anemic domain model"**
  (entities that are pure data bags with all logic living in services).
  Both styles are used in real production Spring codebases; this module
  leans rich-domain for the one invariant that matters (stock non-
  negativity) while keeping broader business orchestration (talking to
  multiple entities, transactions, caching) in the service layer — a
  common, pragmatic middle ground rather than a strict adherence to either
  extreme.

## IDOR note (referenced from `Order.orderNumber`'s Javadoc)

`Order` exposes a separate `orderNumber` business key precisely so the
surrogate `id` (a sequential, auto-incrementing `Long`) never needs to
leave the server as the only external identifier. This isn't just a
naming/URL-friendliness choice — it's a defense against **IDOR**
(Insecure Direct Object Reference, an OWASP Top 10 category): if API
responses and URLs used the raw sequential `id` (`GET /api/v1/orders/47`),
an attacker could trivially enumerate `/orders/1`, `/orders/2`, ...,
`/orders/48` and probe for orders they're not authorized to see — the
sequential nature of the identifier itself becomes an information leak
(revealing roughly how many orders exist) and an enumeration vector. A
non-sequential, non-guessable `orderNumber` (this module's
`ORD-<epoch>-<random>` scheme) doesn't fix authorization by itself — a
real system still needs a proper authorization check on every lookup
(verifying the requesting user/customer actually owns or may view that
order) — but it removes the cheap, no-authorization-bypass-needed
enumeration attack sequential ids invite, and it's a real, current example
of a common Spring/JPA default (auto-incrementing `@GeneratedValue` primary
keys) creating a security consideration engineers reach for the surrogate
key without realizing. See `security/` for the fuller IDOR/authorization
treatment once that module starts.

## `entity/OrderLine.java` / `entity/Order.java`

The `unitPriceAtOrderTime` snapshot (in `OrderLine`) is the concrete
implementation of a PRODUCTION NOTE java-basics only flagged, never
implemented (see that file's Javadoc). Read `Order.addLine`'s Javadoc
carefully — it's the module's clearest illustration of a genuinely common
JPA bug class: bidirectional associations needing BOTH sides kept in sync
manually, because the foreign key (the actual persisted truth) lives on
only one side (`OrderLine.order`).

`Order.transitionTo` is a near-verbatim port of java-basics'
`Order.transitionTo`, swapping only the exception type (`IllegalStateException`
-> `InvalidOrderStateException`, see that class's Javadoc for why) —
proof that the STATE MACHINE LOGIC itself didn't need to change at all
when moving from an in-memory object to a persisted entity; only the
plumbing around it (exception types tuned for HTTP mapping, `@Version` for
concurrent-transition safety) changed.

## `repository/*.java`

None of these interfaces have an implementation class anywhere in this
codebase — Spring Data generates one via a JDK dynamic proxy at startup
(`context.getBean(OrderRepository.class)` returns a proxy instance, not
an instance of some hand-written `OrderRepositoryImpl`). Each interface
demonstrates a different piece of the required repository story:

- `CustomerRepository.findByEmail` — the simplest possible derived query
  method (single equality condition).
- `ProductRepository.findByNameContainingIgnoreCase` — a derived query
  method that ALSO takes a `Pageable`, showing the two aren't separate
  features; any derived (or `@Query`) method can accept a trailing
  `Pageable`/`Sort` parameter.
- `ProductRepository.findLowStock` — `@Query` with JPQL, for a condition
  (`<`) a derived method COULD express (`findByStockQuantityLessThan`) but
  is written explicitly here to show the escape hatch deliberately.
- `OrderRepository.findByIdWithLines` — `@Query` with a `JOIN FETCH`,
  the N+1 fix. Read this method's Javadoc for the full walkthrough of WHY
  N+1 happens and how `JOIN FETCH` + `distinct` fixes it.
- `OrderRepository.searchByCustomerEmail` — `@Query` + `Pageable` combined,
  and a JOIN across two entities (`Order` -> `Customer`) inside the JPQL,
  something a derived query method could technically express
  (`findByCustomerEmailContainingIgnoreCase`) but which stops being
  readable as a method name once more conditions are added — the exact
  point made in `README.md` section 3 about when to reach for
  `Specification`/Querydsl instead.

## `dto/*.java`

Read `CustomerRequest`'s Javadoc first — every other DTO's "why does this
exist" question is answered there once, not repeated. A structural note
worth adding: every DTO in this package is a `record` EXCEPT
`CustomerResponse`, which is a plain class extending
`RepresentationModel<CustomerResponse>` — see that file's Javadoc for why
(HATEOAS's `RepresentationModel` is a mutable base class, and records
can't extend any class).

`OrderResponse.from`/`OrderLineResponse.from` are static factory methods,
not constructors — a deliberate style choice: a constructor named after
the type it's constructing (`new OrderResponse(order)`) is ambiguous about
whether it's copying an existing `OrderResponse` or converting FROM an
`Order`; a named static factory (`OrderResponse.from(order)`) makes the
conversion direction unambiguous at the call site.

## `exception/*.java`

Three custom exception types (`InsufficientStockException`,
`ResourceNotFoundException`, `InvalidOrderStateException`), all unchecked,
plus `ApiError` (the response DTO) and `GlobalExceptionHandler` (the
`@RestControllerAdvice` translating each to an HTTP status). Read
`GlobalExceptionHandler`'s own Javadoc for the full per-exception mapping
rationale — the one thing worth adding here is the DESIGN PRINCIPLE
governing when this module creates a NEW exception type versus reuses an
existing one: a new type is justified only when a caller (here, always
`GlobalExceptionHandler`) needs to treat it differently from every other
exception. `ResourceNotFoundException` is intentionally generic across
Customer/Product/Order (all get an identical 404) — see that class's
Javadoc for the explicit contrast with `InsufficientStockException`, which
DOES need its own type because it needs a different HTTP status AND
structured fields (`sku`/`requested`/`available`) a generic exception
couldn't carry.

## `service/ProductService.java`

The Module 8 (caching) centerpiece — read this file's Javadoc in full; it
contains the cache-aside-vs-write-through comparison and the eviction-
timing nuance at the point where they're most concrete. One thing to trace
through by hand: `reserveStock`'s `@CacheEvict(key = "#result.sku")` only
works because Spring evaluates `#result` AFTER the method body runs
(`beforeInvocation = false` is `@CacheEvict`'s default) — if you ever see
`@CacheEvict(..., beforeInvocation = true)` elsewhere (used when you want
eviction to happen even if the method throws), referencing `#result` in
that mode would fail, since the method hasn't produced one yet.

## `service/OrderService.java`

Read this file's Javadoc in full — it's a direct, paragraph-by-paragraph
contrast with java-basics' `OrderService.placeOrder`, and is the single
most important file in this module for understanding what `@Transactional`
actually buys you (and the nuance about WHEN writes actually hit the
database within a transaction, which most tutorials gloss over).

`listByStatus`'s inline comment about NOT using the JOIN-FETCH query for a
paginated list is worth re-reading after `OrderRepository`'s Javadoc — it's
the concrete instance of the "JOIN FETCH + pagination" pitfall
`README.md` section 3 warns about in the abstract.

## `service/CustomerService.java`

The simplest service in the module, deliberately. Worth noticing what it
does NOT have: no caching, no complex transactional contrast, no state
machine. Not every class in a real system needs equal complexity — this
service exists to give `CustomerController` something real to call, and
resisting the urge to add speculative behavior (see this file's own
Javadoc, "YAGNI") is itself a design decision.

## `controller/*.java`

Read each controller's class-level Javadoc for its specific teaching focus:
`CustomerController` for HATEOAS + the API versioning comparison,
`ProductController` for pagination/sorting/filtering mechanics +
the `Page`/`PageImpl` caveat, `OrderController` for tying validation,
`@Transactional`, and `GlobalExceptionHandler` together in the
order-placement flow. A structural pattern across all three: NO controller
method contains a try/catch — every domain exception (validation failure,
`ResourceNotFoundException`, `InsufficientStockException`,
`InvalidOrderStateException`, an optimistic-lock failure) is allowed to
propagate all the way out to `GlobalExceptionHandler`. This is deliberate
and consistent, not an oversight in any one method.

## `config/CacheConfig.java` / `config/OpenApiConfig.java`

`CacheConfig`'s Javadoc explains why a custom `RedisCacheManager` bean
exists instead of relying on Boot's auto-configured default (per-cache TTL,
JSON serialization instead of JDK serialization). `OpenApiConfig` is much
smaller and mostly cosmetic (document title/description) — springdoc needs
zero configuration to function at all; this bean only improves what a
human reading the generated docs sees.

## `SpringOrdersApplication.java`

The entry point. Read its Javadoc for what `@SpringBootApplication`
actually bundles (`@Configuration` + `@EnableAutoConfiguration` +
`@ComponentScan`) and why `@EnableCaching` sits alongside it here
specifically (Module 8's `@Cacheable`/`@CacheEvict` annotations are
silent no-ops without it — a very common "why isn't my cache working"
first debugging step for anyone new to Spring's caching abstraction).

## `application.yml`

Every setting has an inline comment explaining WHY, not just what — most
worth re-reading in isolation: the `ddl-auto: update` vs. `validate`+
migration-tool discussion, and the `open-in-view: false` discussion (which
directly explains why `OrderService`/DTOs need to be as careful as they are
about WHERE lazy associations get touched).

## `docker-compose.yml` / `pom.xml`

Both are annotated in-file. `pom.xml`'s header comment explains the
Maven-vs-Gradle choice and why each starter is present; `docker-compose.yml`'s
header explains why Docker Compose (vs. natively-installed Postgres/Redis)
is the right default for local development, and previews how Testcontainers
(Module 10 — Testing) automates the same idea for integration tests.
