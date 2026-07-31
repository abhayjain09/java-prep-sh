# Module 5/8 — Interview Questions

Organized by topic, then by level (beginner -> intermediate -> senior ->
scenario), each with an ideal answer outline and likely follow-ups. These
are representative of the Spring/backend round at companies like S&P
Global, JPMorgan, Goldman Sachs (heavy Spring Boot + JPA shops, often with
Oracle rather than Postgres in production — know both), plus the broader
Spring-adjacent bar at Amazon, Microsoft, Google, Oracle, Adobe, Salesforce,
and Atlassian for teams running JVM backends.

---

## Spring Core

**Beginner:** "What is dependency injection, and why does Spring use
constructor injection by convention now instead of field injection?"
*Ideal answer:* DI means a class declares what it needs (via constructor
parameters, typically) rather than constructing its own dependencies —
the container supplies concrete instances. Constructor injection makes
required dependencies explicit and impossible to omit (the class can't be
constructed without them), and it works with a class in a plain unit test
with zero Spring context (`new ProductService(mockRepo)`). Field injection
(`@Autowired` on a field) hides dependencies (only visible by reading every
field), can't be exercised without reflection or a full Spring context in
tests, and lets a class compile with a null dependency that only fails at
runtime.
*Follow-up:* "What's a case where field injection might still be
defensible?" -> Rare — maybe a test-only base class injecting a shared
test fixture, but even there, most teams prefer `@TestConfiguration` with
constructor-injected test doubles.

**Intermediate:** "`@Component`, `@Service`, `@Repository`, `@Controller` —
functionally, are they different?"
*Ideal answer:* All four are meta-annotated with `@Component`, so Spring's
component scan treats them identically for bean registration purposes.
The differences are (1) semantic/documentation — signaling intent to
readers and static analysis tools — and (2) `@Repository` specifically
adds AOP-based translation of persistence-provider-specific exceptions
into Spring's `DataAccessException` hierarchy (relevant even though this
module's repositories are Spring Data interfaces, not manually-annotated
classes, because Spring Data applies the same translation automatically).
*Follow-up:* "Why does exception translation matter?" -> It lets code
catch `DataAccessException` (or its subtypes, like
`ObjectOptimisticLockingFailureException`, used in this module's
`GlobalExceptionHandler`) without depending on Hibernate-specific
exception classes — if the JPA provider were swapped, application code
wouldn't need to change.

**Senior:** "Explain how `@Transactional` and `@Cacheable` are actually
implemented under the hood, and what breaks if you call an annotated
method from within the same class."
*Ideal answer:* Both rely on Spring AOP — at startup, Spring wraps the bean
in a dynamic proxy (JDK dynamic proxy if the bean implements an interface,
CGLIB subclass proxy otherwise) that intercepts calls to annotated methods
BEFORE they reach the real object, and adds the cross-cutting behavior
(open a transaction / check the cache) around the real call. Critically,
this interception only happens for calls that go THROUGH the proxy — i.e.
calls from OUTSIDE the bean, via the reference the container handed out.
A method inside the same class calling `this.anotherAnnotatedMethod()`
bypasses the proxy entirely (it's a plain Java method call on `this`, not
a call through the proxy), so the annotation's behavior silently doesn't
apply — one of the most common real Spring bugs.
*Follow-up:* "How would you fix a genuine need for a self-invoked
transactional method?" -> Inject the bean into itself via
`@Lazy`/`ApplicationContext.getBean(...)` (works but is a code smell), or
—usually better— extract the annotated method into a separate collaborator
bean and call it from there.

**Scenario:** "A teammate says 'we don't need Spring, DI is over-
engineering for our small service.' How do you respond?"
*Ideal answer:* Depends on genuine context, but the core argument for DI
even at small scale is testability and substitutability, not scale per se
— a small service with hard-coded `new`-constructed dependencies is hard
to unit test in isolation and hard to swap an implementation per
environment (a mock in tests, a stub in a local dev profile). The
counter-argument worth acknowledging honestly: a genuinely tiny,
single-file script with no meaningful test surface or environment
variation doesn't need a DI container — pulling in Spring for that would
be over-engineering. The right response distinguishes "we don't need a
framework this heavy" (sometimes true) from "we don't need dependency
injection as a principle" (almost never true past a handful of classes).

---

## Spring Boot

**Beginner:** "What does `@SpringBootApplication` actually do?"
*Ideal answer:* It's a meta-annotation combining `@Configuration`
(this class can declare `@Bean` methods), `@EnableAutoConfiguration`
(Boot inspects the classpath and configures beans automatically based on
what it finds — e.g. an embedded Tomcat if `spring-boot-starter-web` is
present), and `@ComponentScan` (scans this package and sub-packages for
`@Component`-family classes and registers them as beans).
*Follow-up:* "Why does package placement matter?" -> `@ComponentScan`'s
default scan base is the package of the annotated class — if
`SpringOrdersApplication` weren't at the root of
`com.interviewprep.orders.springapp`, sibling/child packages like
`entity`, `service`, `controller` wouldn't be scanned unless the scan base
were configured explicitly.

**Intermediate:** "How does Spring Boot decide whether to auto-configure a
`DataSource`, and what happens if two conflicting configurations are on
the classpath?"
*Ideal answer:* Auto-configuration classes are heavily annotated with
conditional annotations (`@ConditionalOnClass`, `@ConditionalOnMissingBean`,
`@ConditionalOnProperty`) — Boot's `DataSourceAutoConfiguration` only
activates if a `DataSource` class is on the classpath (via the JDBC driver
dependency) AND no `DataSource` bean already exists in the context (if you
define your own `@Bean DataSource`, Boot's auto-configuration backs off
entirely via `@ConditionalOnMissingBean`). Two THIRD-PARTY starters that
both try to auto-configure the same bean type would similarly resolve via
`@ConditionalOnMissingBean` ordering (whichever configuration processes
first "wins" the bean registration; Boot uses `@AutoConfigureOrder`/
`@AutoConfigureBefore`/`@AutoConfigureAfter` to make this ordering
deterministic rather than arbitrary).
*Follow-up:* "How would you debug 'why did Boot wire up X instead of the Y
I expected'?" -> Run with `--debug` (or `debug: true` in
`application.yml`) — Boot prints a `ConditionEvaluationReport` listing
every auto-configuration considered, whether it matched, and why (or why
not).

**Senior:** "Walk through what happens, in order, from `java -jar app.jar`
to the app accepting its first HTTP request."
*Ideal answer:* JVM starts, `main()` calls `SpringApplication.run(...)`;
Spring creates an `ApplicationContext`, resolves the environment
(`application.yml`/env vars/command-line args, in Boot's documented
override-precedence order), runs `@Configuration` classes and
auto-configuration (conditionally activating beans based on the
classpath/properties), instantiates and wires all singleton beans
(including AOP-proxying any `@Transactional`/`@Cacheable` beans),
publishes `ApplicationReadyEvent`/starts the embedded servlet container
(Tomcat), which binds the configured port and begins accepting
connections — the `DispatcherServlet` (Spring MVC's front controller) then
routes each incoming request to the matching `@RequestMapping` method
based on path + HTTP verb.
*Follow-up:* "Where would a slow-starting `@PostConstruct` method or a slow
`@Bean` factory method show up in this timeline, and why does that matter
for container orchestration?" -> It delays `ApplicationReadyEvent` and
therefore the readiness probe passing — in Kubernetes/ECS, a slow startup
means the instance is excluded from load-balancer rotation longer, which
matters directly for deployment/rollout speed and for how aggressively you
can scale up under sudden load.

**Scenario:** "Your team's actuator `/env` endpoint was found exposed
publicly during a security audit, leaking environment variables including
a database password. How did this likely happen, and how do you prevent a
recurrence?"
*Ideal answer:* Almost certainly
`management.endpoints.web.exposure.include: "*"` (or an equivalent
wildcard) somewhere in configuration, exposing every actuator endpoint
including sensitive ones (`/env`, `/heapdump`, `/threaddump`) without
authentication. Fix: curate the exposed list explicitly (as this module's
`application.yml` does — `health,info,metrics,caches` only), put actuator
behind authentication/a separate internal-only port
(`management.server.port`), and add this specific misconfiguration
pattern to a security-review checklist / static-analysis rule so it's
caught before reaching production next time, not just fixed once reactively.

---

## Spring Data JPA

**Beginner:** "What's the difference between `@OneToMany` and
`@ManyToOne`, and which side owns the relationship?"
*Ideal answer:* They describe the SAME relationship from each entity's
perspective (`Order` has many `OrderLine`s; each `OrderLine` belongs to one
`Order`). The OWNING side is whichever entity's table holds the foreign
key column — here, `order_lines.order_id`, so `OrderLine.order`
(`@ManyToOne`) is the owning side, and `Order.lines` (`@OneToMany(mappedBy
= "order")`) is the inverse side. Only changes made through the owning
side are reflected in the database; the inverse side is purely a
convenience for navigating the object graph in Java.
*Follow-up:* "What breaks if you only update the inverse side?" -> See
`Order.addLine`'s Javadoc — adding to `order.lines` alone without also
setting `line.order` persists a row with a NULL foreign key, or (with
certain cascade settings) throws a constraint violation outright.

**Intermediate:** "Explain the N+1 query problem and how you'd detect and
fix it."
*Ideal answer:* Loading a list of N parent entities and then, for each,
lazily accessing a `@OneToMany`/`@ManyToOne` association fires one
additional query PER parent (or per child, for a chain) — 1 query to load
the parents, plus N more, hence "N+1." Detect it via SQL logging
(`show-sql` + `logging.level.org.hibernate.SQL: DEBUG`) showing repeated,
near-identical SELECTs, or via APM tooling (e.g. New Relic/Datadog showing
an unexpectedly high query count per request). Fix with a `JOIN FETCH`
JPQL query (see `OrderRepository.findByIdWithLines`) to load the
association in the SAME query, or `@EntityGraph` as an annotation-based
alternative to hand-written JOIN FETCH JPQL.
*Follow-up:* "Why not just make every association `EAGER` to avoid this
category of bug entirely?" -> EAGER loading happens on EVERY load of the
owning entity regardless of whether that specific use case needs the
association — you trade a sometimes-N+1 problem for an always-larger,
often-unnecessary join on every single query, including ones that never
touch the association at all. It also makes `EntityManager.find()`
unpredictable in query cost, and multiple EAGER `@OneToMany`s on the same
entity can multiply a single query's result set combinatorially. LAZY-by-
default + explicit `JOIN FETCH` where actually needed is the almost-always
correct default.

**Senior:** "Compare optimistic and pessimistic locking for the stock-
decrement use case in this module, and justify which one `Product.version`
implements."
*Ideal answer:* Optimistic locking (`@Version`, used here) assumes
conflicts are RARE: every read carries a version number, and an UPDATE
includes `WHERE version = ?`; if a concurrent transaction already changed
(and incremented) the row's version, the UPDATE affects zero rows and
Hibernate throws an exception the application must handle (here, mapped to
HTTP 409, asking the client to retry). Cost is paid ONLY on an actual
conflict. Pessimistic locking (`SELECT ... FOR UPDATE`, or JPA's
`LockModeType.PESSIMISTIC_WRITE`) assumes conflicts are COMMON: it acquires
a DB-level row lock at read time, serializing all other transactions
wanting that row until the lock holder commits/rolls back — cost is paid
on EVERY read/write attempt, win or lose, in exchange for never having to
handle a "someone else already changed this" failure at the application
level. For typical stock levels (read-heavy, most individual SKUs not
under heavy concurrent contention most of the time), optimistic locking is
the right default; a single extremely hot SKU during, say, a flash sale is
exactly the scenario where pessimistic locking (or an entirely different
approach — a request queue, a dedicated atomic-decrement path bypassing
the ORM, or a Redis-backed distributed counter with async reconciliation
to the DB) starts to outperform optimistic locking's retry storms.
*Follow-up:* "What HTTP status and response would you return to a client
whose request lost an optimistic-locking race, and should the client
retry automatically?" -> 409 Conflict (see `GlobalExceptionHandler
.handleOptimisticLock`) with a message indicating a retry is reasonable;
whether to auto-retry client-side depends on idempotency — a stock
reservation retry needs to be safe to re-attempt (re-reading the current
state and re-validating, not blindly resubmitting the exact same stale
assumptions), which this module's stateless request/response cycle
supports naturally (the client would simply resubmit the same logical
request, which re-reads fresh state).

**Scenario:** "A `GET /orders?status=PENDING` endpoint, paginated at
size=20, starts intermittently returning fewer than 20 orders per page
after someone 'optimized' it by adding a JOIN FETCH on order lines. What
happened, and how do you fix it?"
*Ideal answer:* This is the JOIN FETCH + pagination pitfall documented in
`README.md` section 3 and `OrderService.listByStatus`'s inline comment:
fetch-joining a `@OneToMany` in a paginated query makes the database (or
Hibernate's in-memory fallback) paginate the JOINED ROWS (order+line
combinations), not the distinct parent entities — an order with 5 lines
consumes 5 "row slots" toward the page size instead of 1, so a page can
silently contain fewer distinct orders than requested (or, in Hibernate's
in-memory-pagination fallback for this exact case, load the ENTIRE result
set into memory before paginating, which can be a serious memory/latency
regression under load). Fix: don't fetch-join a to-many association on a
paginated query at all — either accept N+1-shaped lazy loading for the
list view (acceptable at modest page sizes, as this module's
`listByStatus` does) or fetch the ids for the current page first (a
lightweight paginated query with no fetch join), then run a SECOND query
JOIN FETCHing lines for exactly those ids (`WHERE o.id IN (:pageIds)`) —
two queries, but each one correctly bounded and correctly paginated.

---

## REST API Design (CRUD, DTOs, Versioning, Pagination, HATEOAS, OpenAPI, Exception Handling)

**Beginner:** "Why does this module never return a JPA entity directly
from a controller?"
*Ideal answer:* Three concrete failure modes (see `CustomerRequest`'s
Javadoc): lazy-loading serialization failures (touching a LAZY association
outside an open Hibernate session throws, or — worse if `open-in-view` is
left on — triggers a hidden query at serialization time), tight coupling
of the API contract to the database schema (a column rename becomes a
breaking API change), and accidental exposure of internal-only fields
added to the entity later without anyone remembering to hide them.
*Follow-up:* "What's the extra cost of this approach?" -> Mapping code
(entity -> DTO, DTO -> entity) that has to be written and kept in sync —
acceptable, and often automated with MapStruct at scale.

**Intermediate:** "Compare path versioning, header versioning, and
content-negotiation versioning for a REST API. Which does this module use,
and why?"
*Ideal answer:* See `README.md` section 4.2's full comparison table. This
module uses PATH versioning (`/api/v1/...`) for ease of demonstration,
caching-friendliness, and log/documentation visibility, while
acknowledging it's arguably less "pure REST" than content-negotiation
versioning, and that header versioning is a reasonable middle ground many
real APIs use.
*Follow-up:* "How would you migrate existing v1 clients to a v2 with a
breaking change, in a path-versioning scheme?" -> Stand up `/api/v2/...`
alongside `/api/v1/...` (both live simultaneously), migrate clients
incrementally, monitor v1 traffic to near-zero, then deprecate/sunset v1
with advance notice — never a hard cutover with no overlap period for a
real external API.

**Senior:** "Design the pagination contract for a public API you expect
external partners to build against for years. What would you do
differently from this module's `Page<T>`-returning endpoints?"
*Ideal answer:* Avoid returning Spring Data's `Page`/`PageImpl` directly
(this module's expedient choice, explicitly flagged as a caveat in
`README.md` section 4.3) because its JSON shape isn't a guaranteed stable
contract across Spring Data versions. Define an explicit, owned
`PagedResponse<T>` (or adopt a documented standard like cursor-based
pagination for very large/rapidly-changing datasets, which avoids the
"page N shifts under you as rows are inserted/deleted between requests"
consistency problem offset-based pagination has). Version the pagination
contract itself as carefully as the resource shape, since external
partners will have built parsing code against whatever shape you shipped
first.
*Follow-up:* "What's a concrete failure mode of offset-based pagination
under concurrent writes?" -> If a row is inserted before the current
page's offset while a client is paginating through results sorted by
insertion order, every subsequent page shifts by one, causing the client
to either see a duplicate row (already seen on a previous page, now pushed
into the current one) or skip a row entirely — cursor-based pagination
(keying off "give me items after ID/timestamp X" rather than "skip N
rows") avoids this class of bug entirely.

**Scenario:** "A junior engineer wants to fully implement HATEOAS across
every endpoint in this module, believing it's 'more RESTful' and therefore
strictly better engineering. How do you respond in a design review?"
*Ideal answer:* Acknowledge the textbook correctness of the instinct (full
HATEOAS is indeed closer to Roy Fielding's original REST constraints) while
grounding the discussion in this module's actual clients: if the API is
consumed by a small number of known, tightly-coupled frontends (the common
case for an internal API), the marginal discoverability benefit of links
on every resource is low, and the cost (larger payloads, more server-side
link-generation code, more surface area to keep correct as endpoints
evolve) is real and ongoing. Recommend HATEOAS selectively — where it
earns its cost (this module's one example: a customer's self-link plus a
link to their orders, a genuinely useful piece of discoverability) — over
mechanically applying it everywhere "because REST." This is exactly
`README.md` section 4.4's "how often this is actually used in practice"
point, and demonstrating the judgment to push back on a technically-not-
wrong but practically-questionable proposal is itself the senior signal
being evaluated here.

---

## Caching (Module 8)

**Beginner:** "What does `@Cacheable` do, and what's the minimum you need
for it to actually take effect?"
*Ideal answer:* `@Cacheable(value = "cacheName", key = "...")` on a method
means: check the named cache for an entry matching the computed key before
running the method body; return the cached value on a hit, run the method
and store its result under that key on a miss. Minimum requirements: a
`CacheManager` bean must exist (auto-configured once a caching starter +
provider, like this module's `spring-boot-starter-data-redis` +
`CacheConfig`'s `RedisCacheManager` bean, is present), `@EnableCaching`
must be present somewhere in the configuration (see
`SpringOrdersApplication`), and the call must arrive THROUGH the Spring
AOP proxy (an external call from a different bean — a self-call from
within the same class silently skips caching, same root cause as
`@Transactional`'s self-call gotcha in the Spring Core section above).

**Intermediate:** "Compare cache-aside and write-through caching. Which
does this module use, and why?"
*Ideal answer:* See `README.md` section 5's full comparison. Cache-aside
(used here): reads populate the cache lazily on a miss; writes evict
(don't update) the stale entry, letting the next read repopulate it.
Write-through: writes go through the cache, which synchronously updates
both itself and the DB — never stale immediately after a participating
write, at the cost of write latency and a caching layer that has to own
write logic, a poor fit for Spring's annotation-driven, fundamentally
read-cache-oriented abstraction.
*Follow-up:* "What's the 'thundering herd' risk in cache-aside, and how
would you mitigate it?" -> When a popular key expires or is evicted, many
concurrent requests can all miss the cache simultaneously and all hit the
database at once for the same data. Mitigations: a short-lived
"in-flight" lock/marker so only one request repopulates the cache while
others wait briefly for the result (sometimes called request coalescing),
staggered/jittered TTLs so many keys don't expire at the exact same
moment, or a background refresh-ahead strategy that repopulates a key
just before it expires rather than waiting for a miss.

**Senior:** "Walk through exactly what could go wrong with cache
consistency in `ProductService.reserveStock`'s `@CacheEvict`, and how
severe is it in practice for this module?"
*Ideal answer:* `@CacheEvict` fires synchronously when the annotated
method RETURNS, not when the surrounding `@Transactional` transaction
actually COMMITS to the database. If the enclosing transaction (e.g.
`OrderService.placeOrder`, which calls `reserveStock` per line) later
rolls back — say line 3 of 5 fails with `InsufficientStockException` —
lines 1 and 2's cache entries were already evicted, even though their
underlying DB rows never actually changed (the transaction rolled back).
The practical severity here is LOW: eviction without a corresponding
committed change just means the next read misses the cache and
repopulates from the DB with the (unchanged, correct) value — a harmless
extra cache miss, not a correctness bug, because cache-aside always falls
back to the DB as ground truth on a miss. The failure mode that WOULD be
serious is the reverse — a write committing successfully but the
corresponding eviction NEVER firing (e.g. a code path that mutates stock
without going through `ProductService`'s annotated methods) — that
produces a cache silently and indefinitely wrong until its TTL expires.
*Follow-up:* "How would you eliminate even the harmless-eviction-on-
rollback case, if you wanted eviction tied precisely to commit?" ->
`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`
publishing/handling a domain event to perform the eviction only after a
successful commit — more moving parts (an event, a listener), justified
only if even a harmless extra cache miss is unacceptable for a given use
case (rarely worth it for this module's stock-lookup cache; more
plausibly worth it for a cache whose miss path is itself very expensive).

**Scenario:** "Six months after this module ships, a new
'bulk stock correction' admin endpoint is added directly against
`ProductRepository`, bypassing `ProductService` entirely 'for
performance.' Two weeks later, customer support reports customers seeing
wrong stock counts intermittently. Diagnose and fix."
*Ideal answer:* The bulk endpoint mutates `stockQuantity` directly via the
repository, bypassing `ProductService.reserveStock`/`restock` — the ONLY
methods carrying `@CacheEvict`. Any SKU touched by the bulk endpoint keeps
its old (now-wrong) value cached until the `productStock` cache's 2-minute
TTL happens to expire it — exactly the "TTL as a safety-net, not the
primary mechanism" design this module documents (`README.md` section 5),
except here the primary mechanism (explicit eviction) was silently bypassed
entirely, leaving ONLY the safety net, which is far weaker (up to 2 minutes
of staleness instead of near-zero). Fix: route the bulk correction through
`ProductService` (adding a new bulk-aware method there if the single-SKU
methods don't fit), or, if bypassing the service layer is unavoidable for
genuine performance reasons, evict the affected cache keys explicitly from
the bulk endpoint's own code — the invariant to defend going forward is
"every code path that changes `stockQuantity` also evicts the corresponding
cache key," and that invariant needs to be enforced by convention, code
review, or (better) a single choke-point method every mutation path is
required to go through, exactly as `ProductService` was designed to be
before the bulk endpoint bypassed it.
