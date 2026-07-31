# Module 5/8 — Spring Core, Spring Boot, Spring Data JPA, REST APIs, Caching

**Domain used throughout:** the same Order/Inventory system as
[java-basics/](../java-basics/) — `Customer`, `Product`, `Order`,
`OrderLine` — now re-expressed as a real, persistent, network-accessible
Spring Boot service instead of an in-memory, single-process demo. Every
concept below is demonstrated against this same model so the domain logic
you already understand carries forward; only the *shape* of the code (and
the problems that shape solves) changes.

Companion files:
- [diagrams/request-flow.md](diagrams/request-flow.md) — sequence diagram, controller -> service -> repository -> DB
- [diagrams/cache-aside-sequence.md](diagrams/cache-aside-sequence.md) — cache-aside read/write sequence diagram
- [src/](src/) — the actual Spring Boot application
- [pom.xml](pom.xml) — Maven build definition
- [docker-compose.yml](docker-compose.yml) — local Postgres + Redis for development
- [EXPLANATION.md](EXPLANATION.md) — line-by-line walkthrough of every file in `src/`
- [EXERCISES.md](EXERCISES.md) — hands-on exercises
- [INTERVIEW.md](INTERVIEW.md) — beginner/intermediate/senior/scenario interview questions with ideal answers

**This sandbox has no JDK/Maven/Docker.** Every file here is written to
compile and run on a real machine with the right tooling — see
[Build & run](#build--run-once-you-have-a-jdk--maven-locally) at the bottom
for the exact commands.

---

## 0. Design note: why this module defines its own entity classes

`java-basics` models `Customer`, `Product`, and `OrderLine` as **records**.
This module (`spring/`) defines its **own** `Customer`, `Product`, `Order`,
`OrderLine` classes under `entity/` — annotated `@Entity` — rather than
importing java-basics' records. Two questions worth separating: *why not
reuse the records*, and *why a fresh set of classes instead of a shared
module*.

### Why records don't work well as JPA entities

This is a genuinely common senior-interview question, and "records are
immutable" is only part of a complete answer. The full reasoning (also in
`entity/Customer.java`'s Javadoc, since that's where a reader will meet it
in code):

1. **No-arg constructor requirement.** Hibernate hydrates entities from a
   `ResultSet` by calling a no-arg constructor via reflection and then
   populating fields directly — it does not call a fully-parameterized
   constructor with arguments in exactly the right order at exactly the
   point a row is read. A record's *only* constructor is generated from its
   component list; there is no way to add an independent no-arg constructor
   to a record while keeping its compact canonical one. Records are
   structurally the wrong shape here, not just philosophically opposed to
   it.
2. **Mutable state / identity via a generated ID.** A record's entire
   purpose is value semantics: two records with equal fields *are* equal,
   with no independent identity. A JPA entity is the opposite — identity
   comes from a primary key that doesn't exist until after an INSERT, and
   Hibernate needs to *mutate* that field post-construction (and mutate
   other fields when lazy associations resolve later). Records' fields are
   all `private final`; there is no supported way for a persistence
   provider to set them after the fact.
3. **Proxying for lazy loading.** Hibernate very often hands back a
   runtime-generated subclass ("proxy") standing in for an entity not yet
   loaded from the DB, so `@ManyToOne`/`@OneToOne` associations can be
   fetched lazily on first access. Subclassing requires a non-`final`,
   constructible class — records are implicitly `final` and cannot be
   subclassed.

`java-basics/README.md`'s "when NOT to use records" section already flagged
this forward ("Don't use records for JPA entities — entities need mutable
state, identity semantics, and a no-arg constructor that plays awkwardly
with records' all-args canonical constructor"); this module is where that
note becomes concrete, working code.

Note the interesting exception: some newer JPA/Hibernate versions have
*limited* support for using records as **read-only projections** (a query
result shape, not a managed entity) — a genuinely different, narrower use
case from "the entity records are managed against," and out of this
module's scope.

### Why a separate copy instead of one shared domain module

A real multi-module company codebase would likely extract a shared
"domain-core" module both `java-basics` and `spring` depend on. This
teaching repo deliberately does **not** do that: `spring/` is a fully
self-contained Maven project with its own `pom.xml`, so you can read this
one module top-to-bottom and understand the whole persistence-layer story
without a multi-module Maven reactor build or worrying about build order
between folders. The cost is ~100 lines of duplicated domain logic
(`OrderStatus`'s transition rules, mirrored validation) — an explicit,
bounded, and acceptable trade-off for a *teaching* repository; it would be
the wrong call in a real product codebase with real maintenance costs from
drift between two copies.

Treat every entity in this module as **"Customer/Product/Order/OrderLine,
the persistence-ready evolution"** — the same real-world concepts
java-basics introduced, reshaped for what a relational persistence layer
requires, not an unrelated new domain.

---

## 1. Spring Core

### What it is
The IoC (Inversion of Control) container and Dependency Injection (DI): instead
of a class constructing its own collaborators (`new ProductRepository()`
inside `ProductService`), it *declares what it needs* (a constructor
parameter of type `ProductRepository`) and the Spring container supplies a
concrete instance at startup. `@Component`/`@Service`/`@Repository`/
`@Controller` mark a class as something the container should instantiate
and manage (a "bean"); `@Configuration` classes with `@Bean` methods
(`CacheConfig`, `OpenApiConfig` in this module) declare beans explicitly
when auto-detection isn't enough (e.g. a `RedisCacheManager` needs
construction logic, not just a no-arg constructor).

### Why introduced / problem it solves
Without DI, every class manually constructs its dependencies, deeply
coupling it to *concrete* implementations and making substitution (a mock
in a test, a different implementation in a different environment) require
editing the dependent class itself. DI inverts that: a class depends on an
*abstraction* (an interface, or just "some `ProductRepository`") and the
container decides which concrete instance to hand it — enabling both
testability (inject a mock `ProductRepository` in a unit test with zero
changes to `ProductService`) and configuration flexibility (swap a bean's
implementation per Spring `@Profile` without touching consuming code).

### When to use / when not to use
- Use constructor injection (as every class in this module does — see
  `ProductService`'s constructor) for **required** dependencies. It makes
  a class impossible to construct in an invalid state (no dependency can be
  null because the constructor demands it), and it makes dependencies
  visible and testable without any Spring context needed in a unit test
  (`new ProductService(mockRepository)` just works).
- Avoid **field injection** (`@Autowired private ProductRepository repo;`)
  despite still being extremely common in tutorials and legacy code: it
  hides a class's true dependencies (visible only by reading every field),
  makes the class impossible to construct without reflection/a Spring
  context (breaking plain-Java unit tests), and allows a class to compile
  with an unset dependency that only fails at runtime.
- Avoid **setter injection** unless a dependency is genuinely optional and
  has a sensible default absent it — rare in practice; most "optional"
  dependencies are better modeled as `Optional<T>` constructor parameters
  or separate beans entirely.

### Trade-offs & performance implications
- The container itself adds a startup-time cost (classpath scanning, bean
  graph resolution, proxy creation for `@Transactional`/`@Cacheable`) —
  typically hundreds of milliseconds to a few seconds for a service this
  size, which matters for cold-start-sensitive deployments (AWS Lambda,
  covered in `aws/`) but is irrelevant for a long-running server process.
- AOP proxies (see `@Transactional`/`@Cacheable` below) mean a "self-call"
  within the same class — `this.someTransactionalMethod()` called from
  another method in the SAME class — bypasses the proxy entirely and
  silently loses the transactional/caching behavior. This is one of the
  most common real-world Spring bugs; the fix is either injecting the bean
  into itself (awkward) or restructuring so the annotated method is called
  through the Spring-managed proxy from a different bean (the natural,
  usual fix — this module's controllers always call INTO services, never a
  service calling an `@Transactional` method on itself).

### Enterprise examples
- Swapping a `NotificationService` bean's implementation
  (`EmailNotificationService` in production, `LoggingNotificationService`
  in a `local`/`test` Spring profile) with zero changes to any class that
  depends on `NotificationService` — the textbook DI benefit realized.

### Common mistakes
- Field injection everywhere (see above) — a strong "this codebase predates
  DI best-practice awareness" signal in code review.
- Calling an `@Transactional`/`@Cacheable` method on `this` from within the
  same bean and being surprised the annotation "doesn't work" (see the AOP
  proxy trade-off above).
- Circular dependencies between beans (`A` needs `B`, `B` needs `A`) —
  Spring can sometimes resolve these with setter injection or
  `@Lazy`, but a circular dependency is almost always a design smell
  worth fixing by extracting a third component both depend on, not papering
  over with `@Lazy`.

---

## 2. Spring Boot

### What it is
A layer on top of plain Spring that eliminates almost all manual bean
wiring for common scenarios via **auto-configuration**: Boot inspects the
classpath at startup (finds `spring-boot-starter-web`? registers an
embedded Tomcat + `DispatcherServlet`. Finds `spring-boot-starter-data-jpa`
+ a JDBC driver on the classpath + `spring.datasource.*` properties?
registers a `DataSource`, `EntityManagerFactory`, and
`PlatformTransactionManager` automatically) and configures sensible-default
beans you would otherwise write by hand in XML or `@Configuration` classes.
**Starters** (`spring-boot-starter-*`, see `pom.xml`) are curated dependency
bundles — one line pulls in a whole compatible dependency set instead of
hand-picking a dozen individually-versioned jars.

### Why introduced / problem it solves
Pre-Boot ("Spring Framework classic") required substantial manual
`@Configuration`/XML to wire a `DataSource`, transaction manager,
`DispatcherServlet`, view resolver, etc. — most of it boilerplate that was
nearly identical across every project. Boot's auto-configuration is
"convention over configuration": if you follow the conventions (put your
`@SpringBootApplication` class at the root of your component-scanned
package, name your datasource properties the standard way), most of that
wiring simply doesn't need to be written.

### When to use / when not to use
- Use Boot's defaults for the 90% case (this module does, throughout).
- Override a specific auto-configured bean (as this module does with
  `CacheConfig`'s custom `RedisCacheManager`) when the default doesn't fit
  — Boot's auto-configuration is explicitly designed to back off when you
  define your own bean of the same type, which is exactly how `CacheConfig`
  and `OpenApiConfig` coexist with Boot's defaults elsewhere.
- Consider `@SpringBootApplication(exclude = {...})` or profile-specific
  configuration when an auto-configuration actively conflicts with a
  deployment environment (e.g. excluding a security auto-configuration in a
  narrowly-scoped internal tool — generally a red flag worth strong
  justification, covered more in `security/`).

### Trade-offs & performance implications
- "Magic" auto-configuration is a double-edged sword for debugging: when
  something is misconfigured, the failure can surface far from its actual
  cause (a missing `spring.datasource.url` fails inside Boot's
  auto-configuration machinery, not in your code) — `--debug` (or the
  `ConditionEvaluationReport` it prints) shows exactly which
  auto-configurations fired and why, an essential tool once defaults stop
  being convenient.
- Auto-configuration classpath scanning adds real startup latency
  proportional to classpath size — a factor in container cold-start time
  and a reason very latency-sensitive serverless deployments sometimes
  favor a leaner framework or Spring's AOT/native-image compilation path
  (GraalVM native image — out of this module's scope, worth knowing exists).

### Enterprise examples
- Actuator's `/actuator/health` endpoint (enabled here — see
  `application.yml`) is what most container orchestrators (Kubernetes
  liveness/readiness probes, AWS ECS health checks) poll to decide whether
  to route traffic to an instance or restart it — a direct, load-bearing
  production dependency on a single Boot starter.

### Common mistakes
- Exposing `management.endpoints.web.exposure.include: "*"` (every
  actuator endpoint, including `/env` and `/heapdump`) without
  authentication — a real, repeatedly-seen security-review finding (see
  `application.yml`'s comment on why this module curates the exposed list
  instead).
- Leaving `ddl-auto: update` active in a production profile (see
  `application.yml`'s extensive comment on this) — convenient in dev,
  dangerous once real data and real schema-migration discipline matter.

---

## 3. Spring Data JPA

### What it is
`@Entity` classes (`entity/Customer.java` etc.) map Java objects to
relational tables; Spring Data **repositories** (`repository/*.java`) are
interfaces extending `JpaRepository<T, ID>` that Spring implements
*at runtime* via a dynamic proxy — no hand-written implementation class
exists anywhere for `CustomerRepository`/`ProductRepository`/
`OrderRepository`. Method names are parsed into queries ("derived query
methods," e.g. `findBySku`), or you write JPQL explicitly with `@Query` for
anything a method name can't cleanly express (see `OrderRepository`'s
`findByIdWithLines`, a JOIN FETCH that fixes an N+1 query pattern).

### Why introduced / problem it solves
Plain JDBC requires hand-writing SQL, manually mapping `ResultSet` rows to
objects, and manually managing connections/statements/transactions for
every query — enormous repetitive boilerplate. JPA (the specification;
Hibernate is this module's *implementation* of it, auto-configured by
Boot) maps objects to rows once (via annotations) and lets you query
through the object model. Spring Data goes one step further and eliminates
even the repository *implementation* boilerplate JPA alone still requires
(`EntityManager.createQuery(...)` calls) for the common CRUD/query-by-
property case.

### When to use / when not to use
- Use derived query methods (`findBySku`, `findByStatus`) for simple,
  single-condition or few-condition lookups — readable, no SQL/JPQL to
  maintain.
- Use `@Query` with JPQL once a query needs a JOIN FETCH (N+1 fixes),
  an aggregate/projection, or a condition shape a method name would make
  unreadably long (see `OrderRepository.searchByCustomerEmail`).
- Reach for `Specification`/Querydsl (not used in this module, but worth
  knowing exists — see `EXPLANATION.md`) once an endpoint needs to combine
  many *optional*, independently-composable filters — `OrderController.list`
  is already at the edge of what a hand-written if/else filter dispatch
  comfortably handles with two filters; a fifth optional filter would be
  the point to reach for a `Specification`-based dynamic query builder
  instead of a sixth `if`.
- **Don't** default every `@ManyToOne`/`@OneToOne` to Hibernate's `EAGER`
  default — this module overrides both to `FetchType.LAZY` everywhere (see
  `OrderLine`'s Javadoc) and fetches explicitly via JOIN FETCH only when a
  use case actually needs the association loaded.

### Trade-offs & performance implications
- **The N+1 query problem** (see `OrderRepository.findByIdWithLines`'s
  Javadoc for the full walkthrough): loading a list of orders and then
  lazily touching each one's lines/products fires one query per
  association access — 1 (orders) + N (lines per order) + more (products
  per line). JOIN FETCH collapses this to one query, at the cost of a
  wider result set (and see the same Javadoc's `distinct` note for the
  row-duplication gotcha JOIN FETCH introduces over a one-to-many).
- **JOIN FETCH + pagination is a known pitfall** this module deliberately
  documents rather than falls into silently: fetch-joining a
  `@OneToMany` in a query that's also paginated makes Hibernate paginate
  the JOINED ROWS (order+line combinations), not the parent entities —
  you can silently get fewer distinct orders per page than the requested
  page size, or (with Hibernate's in-memory pagination fallback for this
  case) load the *entire* result set into memory before paginating, which
  defeats pagination's whole purpose. `OrderService.listByStatus`
  deliberately does NOT use the JOIN FETCH query for exactly this reason —
  see that method's inline comment.
- **`@Version` (optimistic locking)** costs one extra `WHERE version = ?`
  clause and one extra column, essentially free — but a failed optimistic
  lock means a wasted read+compute cycle plus a client-visible 409 requiring
  a retry, which matters under HIGH CONTENTION on one row (see
  `Product.java`'s Javadoc for the optimistic-vs-pessimistic-locking
  trade-off, and `database/`'s forthcoming module for the full treatment).
- **`open-in-view: false`** (this module's choice, see `application.yml`)
  trades convenience (lazy associations "just working" from anywhere) for
  correctness and explicitness (every lazy access must happen inside a
  deliberate `@Transactional` boundary) — the right default for a REST API
  returning DTOs; the wrong default (in the sense of unnecessary
  restriction) only for server-rendered-template apps that genuinely want
  to lazy-load from the view layer, which this module's REST-only API isn't.

### Enterprise examples
- Nearly every mid-to-large Java shop doing relational persistence in 2024+
  uses Spring Data JPA (or its close cousin, plain Hibernate) over raw
  JDBC — raw JDBC survives mainly in extremely performance-critical hot
  paths or legacy codebases predating Spring Data's maturity.
- The `@Version` optimistic-locking pattern this module uses on
  `Product.stockQuantity` is close to verbatim what real e-commerce/ERP
  systems do for concurrent stock decrements at moderate contention levels.

### Common mistakes
- Forgetting that a bidirectional `@OneToMany`/`@ManyToOne` association has
  an "owning side" (holds the foreign key) and an "inverse side" — mutating
  only the inverse side's collection (`customer.getOrders().add(order)`
  without also `order.setCustomer(customer)`) persists nothing, because
  JPA only looks at the owning side's field to decide what foreign key to
  write. See `Order.addLine`'s Javadoc for the fix this module applies.
- Using Lombok's `@Data` (or writing equals/hashCode over ALL fields) on an
  entity — see `Customer.java`'s Javadoc for why natural-key-based
  equals/hashCode is the correct pattern instead.
- Calling `.save()` on an entity that's already managed (loaded via a
  repository call within the current transaction) out of habit — harmless
  here (Spring Data's `save()` on an already-persistent entity with a
  non-null id just delegates to `merge`, which is a no-op-ish
  re-attach if the entity is already attached) but a sign of not
  understanding dirty-checking, which DOES matter once you're reasoning
  about performance (an unnecessary `merge` can trigger an unnecessary
  SELECT in some circumstances) or debugging why a change wasn't persisted
  (usually the reverse mistake — a DETACHED entity's changes silently not
  persisting because nothing ever called `save()`).

---

## 4. REST API Design

### 4.1 CRUD, DTOs, and validation

**What it is:** Each resource (`Customer`, `Product`, `Order`) gets standard
HTTP-verb-mapped endpoints (`POST` create, `GET` read, `PUT`/`PATCH`
update, `DELETE` remove) operating on **DTOs** (`dto/*.java`), never on
`@Entity` classes directly, validated via Jakarta Bean Validation
(`@Valid` + `@NotBlank`/`@Email`/`@Positive`/etc.).

**Why DTOs, never entities, over the wire** — the full reasoning lives in
`dto/CustomerRequest.java`'s Javadoc (referenced from every other DTO
rather than repeated): lazy-loading serialization failures, tight coupling
of the API contract to the DB schema, and accidental exposure of internal
fields are the three concrete failure modes a DTO boundary prevents.

**When to use / when not to use:** always use a DTO boundary for anything
beyond a genuine, permanent, internal-only microservice where the "client"
is a tightly-coupled sibling service deployed in lockstep (even then, many
teams still prefer the DTO boundary for the same reasons, just with lower
stakes if violated).

**Trade-offs:** DTOs mean mapping code (`OrderResponse.from(order)`, etc.)
— extra classes and extra lines versus serializing an entity directly.
This module writes that mapping by hand (static factory methods) for
transparency; larger codebases often introduce MapStruct (compile-time
code-generated mapping) to eliminate the boilerplate once the number of
DTOs grows large — a good "why didn't you use X" interview answer:
MapStruct trades a little build-tool complexity for eliminating
error-prone hand-written mapping code at scale; hand-written mapping is
more transparent and dependency-free for a smaller, teaching-scale
codebase like this one.

**Common mistakes:** binding a `@RequestBody` directly to an `@Entity`
(mass-assignment risk — a client could set `id`/`version`/other
server-only fields); forgetting `@Valid` on a NESTED collection inside a
DTO (see `OrderRequest`'s Javadoc — Bean Validation does not cascade into
nested objects/collections without it).

### 4.2 API versioning

**What it is:** this module versions its API via the URL path
(`/api/v1/...`, see every `@RequestMapping` in `controller/`).

**The three common approaches and their trade-offs** (see
`CustomerController`'s Javadoc for the same comparison at the point of
use):

| Approach | Example | Pros | Cons |
|---|---|---|---|
| **Path versioning** (used here) | `/api/v1/customers` | Trivially cacheable per-version by any HTTP cache/CDN with zero config; obvious in logs/docs; easy to explore manually (curl, browser) | Arguably violates "a URL identifies a resource, not a resource+protocol-version"; two URLs for "the same" conceptual resource across versions |
| **Header versioning** | `X-API-Version: 2` | Keeps the URL stable/clean across versions | Invisible in default access logs; intermediate caches need explicit `Vary` config; harder to explore casually |
| **Content-negotiation versioning** | `Accept: application/vnd.company.orders-v2+json` | Closest to Roy Fielding's original REST intent (URL = resource identity; version is a representation detail negotiated like any content type) | Least ergonomic in practice — awkward to test manually, more client-library friction |

**Why this module picked path versioning:** it's the easiest to teach,
demo, and curl by hand, and is common enough in real APIs (Twilio, many
internal enterprise APIs) to be a defensible default — but a senior
candidate should present all three with trade-offs, not assert one is
objectively "more correct" without qualification.

### 4.3 Pagination, sorting, filtering

**What it is:** every list endpoint (`ProductController.search`,
`OrderController.list`, `CustomerController.list`) accepts a Spring Data
`Pageable` (bound automatically from `?page=&size=&sort=` query params) and
returns a `Page<T>`. Filtering is a plain optional `@RequestParam`
(`name`, `status`, `customerEmail`) dispatched to different repository
methods.

**Why introduced / problem it solves:** returning an entire table in one
response doesn't scale past a trivial dataset size — pagination bounds
response size and DB work per request; sorting lets a client request a
meaningful order without the server hardcoding one; filtering narrows
results without a client fetching everything and filtering client-side
(wasteful, and potentially insecure if the "everything" includes records
the client shouldn't otherwise be able to enumerate).

**Trade-offs:** returning Spring Data's `Page`/`PageImpl` directly (as this
module does, for brevity) triggers a logged warning in recent Spring Data
versions because `PageImpl`'s JSON shape isn't a guaranteed stable
contract across library versions. Production code more often defines a
small custom `PagedResponse<T>` record with exactly the fields the team
wants to guarantee (content, page, size, totalElements, totalPages) —
the same "own your contract, don't expose an implementation detail"
principle DTOs apply to entities, applied one layer up to the *collection*
response shape.

**Common mistakes:** no default page size (`@PageableDefault` — see every
controller's paginated methods) — an omitted `size` param without a
default risks a client (accidentally or maliciously) requesting an
enormous page in one request; allowing unrestricted `sort` on any field
without validating it's actually a real, indexed column (a sort on an
unindexed column at scale can be a serious, easy-to-miss performance
cliff — flagged here, fully explored in the forthcoming `database/`
module's indexing section).

### 4.4 HATEOAS

**What it is:** demonstrated on exactly one endpoint,
`CustomerController.getById` — the response includes `_links` (a `self`
link and a link to the customer's orders) built via Spring HATEOAS's
`RepresentationModel`/`linkTo(methodOn(...))`.

**How often this is actually used in practice — be honest about this in an
interview:** internal APIs (backend + its own known frontend, or
service-to-service calls between teams that coordinate deploys) very
commonly skip HATEOAS entirely — URL templates are already known at
compile time on the client, and the payload-size/server-complexity cost of
generating links buys little. Public APIs serving many independent, unknown
consumers (Stripe, GitHub, PayPal) use hypermedia-style links MORE, but
usually a pragmatic subset (pagination/navigation links) rather than links
describing every possible state transition on every resource. Full
"hypermedia-driven" API design (discoverable state machines entirely via
links) is genuinely rare in production; know it well enough to implement
it (this module does, once), and be honest in an interview that most real
systems get more value from good OpenAPI documentation than from full
HATEOAS.

### 4.5 OpenAPI

**What it is:** `springdoc-openapi-starter-webmvc-ui` (see `pom.xml`)
generates an OpenAPI 3 spec from controller method signatures plus
`@Operation`/`@ApiResponse`/`@Tag`/`@Parameter` annotations (used
throughout `controller/`), served at `/v3/api-docs` with an interactive
Swagger UI at `/swagger-ui.html` — no separate spec file to hand-maintain
and let drift from the actual code.

**Why introduced:** hand-maintained API documentation (a wiki page, a
separate YAML file) reliably drifts out of sync with the actual
implementation. Generating docs FROM the code (annotations included)
means the documentation can never describe an endpoint that doesn't
exist, and a changed method signature is far more likely to be noticed
(the annotations sit right next to the code being changed) than a
separate document would be.

**Common mistakes:** annotating a DTO's Javadoc but never adding
`@Schema`/`@Operation` annotations at all and assuming springdoc "does
docs" automatically to a useful standard — the base auto-generated spec
(types + HTTP methods + paths) is a starting point, not a substitute for
the human-readable `summary`/`description` text that makes generated docs
actually useful to a consumer who isn't already the author.

### 4.6 Global exception handling

**What it is:** `exception/GlobalExceptionHandler.java`, a single
`@RestControllerAdvice` mapping every exception type this module throws to
a consistent `ApiError` JSON shape and the correct HTTP status: 409 for
`InsufficientStockException`/`InvalidOrderStateException`/optimistic-lock
failures, 404 for `ResourceNotFoundException`, 400 (with structured
per-field errors) for Bean Validation failures, and a generic, non-leaking
500 for everything else.

**Why one class for everything, not try/catch per controller:** every
controller method in this module has ZERO try/catch blocks — they let
domain exceptions propagate and trust the one global handler to translate
them consistently. This is exactly the pattern java-basics' exception
Javadocs previewed ("the natural handling point is one global exception
handler translating it into a 409 Conflict response") — this module is
where that preview becomes real code.

**Why leaking stack traces to API responses is a security anti-pattern**
(the required discussion, and see `GlobalExceptionHandler.handleUnexpected`'s
Javadoc for the same point at the code level): a stack trace or raw
exception message in an HTTP response can reveal internal implementation
details — package/class names ("this is a Spring Boot 3.3 app using
Hibernate" — a technology fingerprint useful for planning further
attacks), SQL fragments (hints at SQL-injection-relevant structure), file
paths, or fragments of business logic that should stay internal. The full
detail (message + stack trace) is logged at `ERROR` server-side (see the
handler's `log.error(...)` call), where an engineer with legitimate access
is the correct audience — not an arbitrary, possibly hostile, API caller.
OWASP explicitly calls this class of issue out; expect it in both
interviews and real security reviews (see `security/` for the fuller
treatment).

---

## 5. Caching (Module 8)

### What it is
The Spring Cache abstraction (`@EnableCaching`, `@Cacheable`,
`@CacheEvict`) provides a provider-agnostic caching API; this module backs
it with Redis (`spring-boot-starter-data-redis` + a custom
`RedisCacheManager` bean in `config/CacheConfig.java`) rather than an
in-memory cache, so the cache is shared across every instance of the
service in a real multi-instance deployment (an in-memory cache like
Caffeine would be per-instance — fine for some use cases, wrong for
anything requiring a consistent view across instances, like stock levels).

### Why introduced / problem it solves
`ProductService.getStockBySku` is exactly the kind of read this module's
domain does often and cheaply serves from cache: read-heavy relative to
writes, and a Postgres round-trip (even a fast, indexed one) costs more
than a Redis round-trip. Caching moves that cost off the database for the
common case, at the cost of the correctness questions below.

### Cache-aside vs. write-through (the required comparison)
- **Cache-aside (used in this module — see `ProductService`'s Javadoc and
  `diagrams/cache-aside-sequence.md`):** the application checks the cache
  first (`@Cacheable`); on a miss, it reads the DB and populates the cache
  for next time. Writes go straight to the DB and simply **evict**
  (`@CacheEvict`) the now-stale entry rather than updating it — the cache
  is always repopulated *by a subsequent read*, never *by a write*. Simple
  to reason about, and the cache can be wiped entirely at any time with
  zero data-loss risk (the DB is always the source of truth). Main risk: a
  "thundering herd" of DB reads immediately after a popular key is evicted
  or expires, all missing the cache simultaneously.
- **Write-through (not used here):** every write goes through the cache,
  which synchronously writes to the DB and updates itself as part of the
  same operation — the cache is never stale immediately after a write it
  participated in. Better staleness guarantees, at the cost of write
  latency (every write waits on both cache and DB) and requiring the
  caching layer to own write logic — a poor fit for Spring's
  annotation-driven cache abstraction, which is fundamentally a
  read-cache/evict-on-write model without substantial custom plumbing.

### TTL choice: staleness vs. load
`CacheConfig` sets a **2-minute** TTL specifically for the `productStock`
cache (shorter than the 10-minute default for other, hypothetical, slower-
changing caches) — see that class's Javadoc for the full reasoning. The
core trade-off: a shorter TTL means the cache reflects reality faster after
any write the explicit `@CacheEvict` path might have missed (a batch job
writing to the DB directly, a crashed transaction's stale read), at the
cost of pushing more reads back to Postgres as entries expire more often.
A longer TTL protects the DB harder but risks serving a stale stock count
for longer — worst case, telling a customer something is in stock right as
it sells out elsewhere. This module treats the explicit `@CacheEvict` calls
as the PRIMARY invalidation mechanism and the TTL as a safety-net backstop,
not the other way around.

### "The two hard things in computer science are cache invalidation and naming things"
The joke (usually attributed to Phil Karlton) is funny because it's true in
exactly the way this module's `ProductService` Javadoc works through:
`@CacheEvict` must remove the stale entry in a way that's correctly
synchronized with the DB write that made it stale. Get the ORDER or TIMING
wrong (evict before the write actually lands, or fail to evict at all on
one of several mutation paths) and the cache silently serves wrong data —
with no error, no exception, no log line, just a customer told something is
in stock that isn't. This module's honest treatment of the nuance (see
`ProductService`'s Javadoc on eviction timing relative to `@Transactional`
commit) is deliberately more cautious than "just add `@CacheEvict` and
move on" — that caution IS the lesson.

### When to use / when NOT to use caching
- Use it for read-heavy, moderately-changing data where a *little*
  staleness is an acceptable trade-off for load reduction (stock counts,
  product catalog data, computed aggregates).
- **Don't** cache data where staleness has an outsized cost relative to the
  read-load savings (e.g. an account balance right before a withdrawal
  decision — most financial systems either don't cache this or use a very
  short TTL plus strict server-side re-validation before any action that
  spends the balance) — and never cache anything permission-sensitive
  (a cached response including data a *specific* user is authorized to see
  can leak across users if the cache key doesn't fully capture the
  authorization context — a real, serious security bug pattern, not a
  theoretical one).

### Enterprise examples
- Nearly every e-commerce platform caches product-catalog and stock
  read paths in front of the primary database — this module's shape
  (Spring Cache abstraction + Redis + cache-aside + short TTL + explicit
  eviction on writes) is close to verbatim what many mid-size platforms
  actually run.

### Common mistakes
- Caching a method's result keyed by ALL its arguments by default (Spring
  Cache's default `SimpleKeyGenerator`) instead of an explicit `key = "#sku"`
  — a signature change (adding a parameter) can silently and invisibly
  change every cache key's shape, invalidating an entire cache's worth of
  entries with no warning (see `ProductService.getStockBySku`'s explicit
  `key` for why this module never relies on the default).
- Forgetting to evict on EVERY mutation path that changes cached data —
  `ProductService` has two (`reserveStock`, `restock`); a THIRD mutation
  path added later (e.g. a bulk stock-correction endpoint) that forgets
  `@CacheEvict` would silently reintroduce staleness with no test likely
  to catch it unless staleness is specifically tested for.
- Caching `null`/"not found" results without a deliberate reason — this
  module's `CacheConfig` explicitly calls `.disableCachingNullValues()`;
  caching "not found" can hide a resource that gets created moments later
  behind a stale negative cache entry until the TTL expires.

---

## 6. Inventory: entity vs. field (the required design decision)

See `entity/Product.java`'s Javadoc for the full write-up; summarized here
because it's one of this module's most important calls to defend in an
interview.

**Decision: `stockQuantity` is a field on `Product`, not a separate
`Inventory` entity/table.**

java-basics' `Inventory` is a `Map<String, Integer>` deliberately decoupled
from `Product`/`Order` — a fine design in-memory, where "decoupled" mostly
just means "a different Java class with its own file." In a relational
schema, splitting stock into its own table only earns its cost when there's
a genuine **additional dimension** to the data — most commonly **multiple
warehouses/locations** (stock becomes a function of `(product, warehouse)`,
which needs its own table with a composite unique constraint), or a
requirement to **audit every individual stock movement** as its own
immutable row (a stock-ledger table, layered ON TOP of a simple quantity
column in a mature system, not instead of it).

This module has exactly one implicit warehouse and no requirement to audit
individual movements as separate rows — a separate `inventory` table would
add a join to every stock check and a foreign key to manage for zero real
benefit: normalization for its own sake, not because any actual query or
constraint needs it.

**The honest trade-off, stated plainly:** if this were a real multi-
warehouse system, this decision is wrong from day one, and retrofitting a
separate `Inventory` entity later is a real migration — a new table,
backfilling existing `stockQuantity` values as `warehouse = "DEFAULT"`
rows, and updating every query plus the cache-aside logic in
`ProductService`. A senior engineer's job in a design review is to name
that trade-off explicitly rather than silently assume single-warehouse
forever ("premature generalization" — an unneeded `Inventory` table today —
and "premature specialization" — baking in single-warehouse assumptions
that bite later — are both real risks; this module picks the simpler
option because the assignment's actual scope doesn't call for
multi-warehouse, not because simpler is always correct).

---

## Build & run (once you have a JDK + Maven locally)

```bash
# 1. Start local Postgres + Redis (requires Docker — not available in this sandbox)
cd spring
docker compose up -d

# 2. Build and run the tests
mvn clean verify

# 3. Run the application
mvn spring-boot:run
# or: mvn clean package && java -jar target/spring-orders-api-0.0.1-SNAPSHOT.jar

# 4. Explore
open http://localhost:8080/swagger-ui.html      # interactive API docs
curl http://localhost:8080/actuator/health      # health check
```

Prerequisites: JDK 21, Maven 3.9+, Docker (for `docker-compose.yml`'s
Postgres + Redis). See the root [README.md](../README.md) for the overall
prerequisites list.

## Next module

Module 6 — Security (Spring Security, JWT, OAuth2, OIDC, SAML, OKTA, SCIM,
MFA, RBAC, CORS, CSRF) will add an authentication/authorization layer in
front of exactly these endpoints — not started until this module is
confirmed solid.
