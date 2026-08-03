# Senior Java Fullstack Developer — Interview Prep

Covers: Java 11+ core, Angular, AWS + Okta, Spring Boot/Jakarta EE, secure REST API design, Oracle/PostgreSQL SQL & performance tuning, and AWS delivery practices (IaC, CI/CD, monitoring).

---

## Part 1: Java (11+) Core

### Q1. What are the major features introduced in Java 9–17 that you'd highlight in an interview?
**Answer:**
- **Java 9**: Module system (JPMS/Project Jigsaw), `var` in try-with-resources, JShell (REPL), private interface methods.
- **Java 10**: Local variable type inference (`var`).
- **Java 11 (LTS)**: New `HttpClient` API (replaces `HttpURLConnection`), `String` methods (`isBlank()`, `strip()`, `repeat()`, `lines()`), running single-file source code directly (`java Hello.java`), removal of Java EE modules (JAXB, CORBA) from JDK.
- **Java 14–16**: Switch expressions (`->` syntax, `yield`), Records (immutable data carriers), `instanceof` pattern matching, Text blocks (`"""`), sealed classes (preview).
- **Java 17 (LTS)**: Sealed classes finalized, pattern matching for switch (preview), strong encapsulation of JDK internals by default.

Interviewers care most about **11 → 17 delta** since most enterprise shops moved from 8 to 11/17. Be ready to explain records vs Lombok, and sealed classes vs traditional inheritance.

### Q2. Explain Java's memory model — heap, stack, metaspace — and how garbage collection works.
**Answer:**
- **Stack**: per-thread, stores local variables and method call frames; automatically reclaimed on method return; `StackOverflowError` on deep/infinite recursion.
- **Heap**: shared across threads, stores objects; divided into **Young Generation** (Eden + 2 Survivor spaces) and **Old Generation (Tenured)**. New objects are allocated in Eden; objects surviving multiple minor GCs get promoted to Old Gen.
- **Metaspace** (replaced PermGen since Java 8): stores class metadata; grows into native memory instead of a fixed heap region, avoiding the old `OutOfMemoryError: PermGen space` issue.
- **GC algorithms**:
  - **Serial GC**: single-threaded, good for small heaps.
  - **Parallel GC**: multi-threaded throughput collector, default pre-9.
  - **G1 (Garbage First)**: default since Java 9; region-based, aims for predictable pause times, good general-purpose choice.
  - **ZGC / Shenandoah**: low-latency collectors (sub-millisecond pauses) for very large heaps, available as production-ready in Java 15+/17.
- Minor GC cleans Young Gen (stop-the-world but fast); Major/Full GC cleans Old Gen (more expensive). Tuning usually means picking the right collector + sizing `-Xms`/`-Xmx`/`-Xmn` and monitoring pause times via GC logs.

### Q3. What's the difference between `==` and `.equals()`, and how do you correctly override `equals()`/`hashCode()`?
**Answer:** `==` compares references (identity) for objects, values for primitives. `.equals()` compares logical equality and must be overridden for value semantics. Contract:
- **Reflexive, symmetric, transitive, consistent**, and `x.equals(null)` must return false.
- If you override `equals()`, you **must** override `hashCode()` so equal objects produce equal hash codes (otherwise hash-based collections like `HashMap`/`HashSet` break).
- Use `Objects.equals()` and `Objects.hash()` for clean implementations, or use **Records** in Java 14+/16+ which generate both automatically based on all components.

### Q4. Explain functional interfaces, lambdas, and the Streams API — when would you *not* use streams?
**Answer:** A functional interface has exactly one abstract method (e.g., `Runnable`, `Comparator`, `Function<T,R>`, `Supplier<T>`) and can be implemented with a lambda. Streams provide a declarative pipeline (`filter`, `map`, `reduce`, `collect`) over collections, supporting lazy evaluation and parallelism (`parallelStream()`).

**When to avoid streams:**
- Simple loops where a `for` loop is more readable/debuggable.
- When you need to break early with complex control flow (streams don't support `break`/`continue` cleanly).
- Performance-critical hot paths with small collections — the lambda/functional overhead and boxing can be slower than an imperative loop.
- When exceptions need to be thrown from within lambda bodies — checked exceptions require awkward wrapping.

### Q5. Explain `CompletableFuture` and how you'd use it for async orchestration.
**Answer:** `CompletableFuture<T>` represents a future result of an async computation, supporting composition:
- `supplyAsync()` / `runAsync()` to kick off work (optionally on a custom `Executor`).
- `thenApply()`/`thenCompose()` to transform/chain results.
- `thenCombine()` to join two independent futures.
- `exceptionally()` / `handle()` for error handling.
- `allOf()` / `anyOf()` to wait on multiple futures.

Typical use: calling two microservices in parallel and combining results, or offloading blocking I/O to a dedicated thread pool so the main request thread isn't blocked — important in high-throughput Spring Boot services.

### Q6. What's the difference between checked and unchecked exceptions? How do you design exception handling in a large codebase?
**Answer:** Checked exceptions (extend `Exception`, not `RuntimeException`) must be declared or caught (`IOException`, `SQLException`) — they represent recoverable conditions. Unchecked (`RuntimeException` subclasses) represent programming errors (`NullPointerException`, `IllegalArgumentException`) and don't need declaration.

**Design approach for large codebases:**
- Define a small hierarchy of custom **unchecked** business exceptions (e.g., `ResourceNotFoundException`, `ValidationException`, `ConflictException`) so callers aren't forced into try/catch boilerplate.
- Centralize handling with `@ControllerAdvice`/`@ExceptionHandler` in Spring to map exceptions to consistent HTTP error responses.
- Avoid catching generic `Exception` and swallowing it; always log with context and rethrow or wrap.

---

## Part 2: Angular

### Q7. Explain Angular's change detection and `OnPush` strategy.
**Answer:** Angular's default change detection runs on every async event (click, HTTP response, timer) and checks the entire component tree top-down (Zone.js patches async APIs to trigger this). `ChangeDetectionStrategy.OnPush` tells a component to skip checking unless: (1) an `@Input()` reference changes, (2) an event originates from within the component, or (3) you manually call `markForCheck()`/use an `Observable` with the `async` pipe. This drastically reduces unnecessary checks in large component trees — a key performance lever, especially combined with immutable data patterns (never mutate an `@Input()` object in place).

### Q8. RxJS: difference between `Subject`, `BehaviorSubject`, and `ReplaySubject`; and `switchMap` vs `mergeMap` vs `concatMap`.
**Answer:**
- **Subject**: multicast observable with no memory of past values; late subscribers get nothing until the next emission.
- **BehaviorSubject**: requires an initial value, always emits the *current* value to new subscribers — great for shared state (e.g., current user, auth state).
- **ReplaySubject**: replays the last N emitted values to new subscribers, regardless of timing.

- **switchMap**: cancels the previous inner observable when a new source value arrives — ideal for typeahead search (only care about the latest request).
- **mergeMap**: runs all inner observables concurrently, no cancellation — good when order doesn't matter and you want parallelism.
- **concatMap**: queues inner observables and runs them strictly in order, one at a time — good when order matters (e.g., sequential API calls that depend on each other).

### Q9. How do you implement route guards and secure routes tied to an identity provider like Okta?
**Answer:** Angular route guards (`CanActivate`, `CanActivateChild`, `CanLoad`, or the functional `canActivateFn` in Angular 15+) intercept navigation. With Okta:
- Use `@okta/okta-angular` which provides `OktaAuthGuard` — wraps routes so unauthenticated users are redirected to the Okta-hosted login (or embedded widget) before the route resolves.
- `OktaAuthModule`/`OktaAuth` service manages the token lifecycle (access token, ID token, refresh token) using OIDC/PKCE flow.
- An `HttpInterceptor` attaches the bearer access token to outgoing API calls automatically and handles 401 responses (e.g., trigger silent renewal or force re-login).
- Store minimal state in Angular; rely on Okta SDK's `TokenManager` for renewal rather than manually managing tokens in `localStorage` (reduces XSS exposure).

### Q10. How do you optimize an Angular app's bundle size and load performance?
**Answer:**
- **Lazy loading** feature modules via the router (`loadChildren`) so initial bundle only contains what's needed for the first view.
- **Standalone components** (Angular 14+) to reduce NgModule overhead and enable finer-grained tree-shaking.
- **OnPush** change detection + immutable data to reduce runtime CPU cost (not bundle size, but perceived perf).
- **differential loading**/modern build targets, `ng build --prod` with AOT compilation (default), and budgets in `angular.json` to fail CI if bundles exceed thresholds.
- Use the **Angular CLI bundle analyzer** (`webpack-bundle-analyzer`) to spot large dependencies; replace heavy libraries (e.g., moment.js → date-fns/luxon).
- Enable **HTTP caching**, image lazy-loading (`loading="lazy"`), and CDN-hosted static assets.

### Q11. How would you structure state management in a large Angular app — NgRx vs services with `BehaviorSubject`?
**Answer:** For small-to-medium apps, a service exposing a `BehaviorSubject`/signal as the single source of truth is often enough and much simpler to maintain. **NgRx** (Redux pattern: store, actions, reducers, effects, selectors) is worth the overhead when:
- State is shared across many unrelated feature modules.
- You need time-travel debugging, strict unidirectional data flow, or complex async side-effect orchestration (`Effects`).
- Multiple teams work on the same app and need enforced conventions.

Trade-off to mention: NgRx adds boilerplate and a learning curve; for a mid-sized CRUD app, an over-engineered NgRx setup can slow the team down. Signals (Angular 16+) are increasingly used as a lighter-weight reactive primitive that reduces the need for RxJS/NgRx in many cases.

---

## Part 3: AWS Core Services + Okta (Security Depth)

### Q12. Walk through a typical AWS architecture for a Spring Boot + Angular application.
**Answer:** A common pattern:
- **Angular** built as static assets served from **S3** behind **CloudFront** (CDN + HTTPS via ACM cert).
- **Spring Boot** API packaged as a container image, deployed on **ECS (Fargate)** or **EKS**, fronted by an **Application Load Balancer (ALB)**.
- **RDS** (PostgreSQL/Oracle) or **Aurora** for the database, in private subnets.
- **Secrets Manager**/**Parameter Store** for DB credentials and API keys (never hardcoded or in env files checked into source).
- **Route 53** for DNS, **WAF** in front of CloudFront/ALB for basic protection (SQLi, XSS rules).
- **CloudWatch** for logs/metrics/alarms, **X-Ray** for distributed tracing.
- **Okta** as the external IdP, integrated via OIDC — the Angular SPA authenticates with Okta directly; the ALB or API Gateway can also validate the JWT before it hits Spring Boot, or Spring Security validates it inline via a resource-server configuration.

### Q13. How does OIDC/OAuth2 authentication flow work with Okta in a SPA + API setup?
**Answer:** Typical flow is **Authorization Code with PKCE** (recommended over implicit flow for SPAs since implicit is deprecated):
1. Angular app redirects user to Okta's `/authorize` endpoint with a code challenge.
2. User authenticates at Okta (or via SSO if already logged in).
3. Okta redirects back with an authorization code.
4. The Okta Angular SDK exchanges the code + code verifier for tokens at the `/token` endpoint (no client secret needed for public/SPA clients — PKCE replaces that).
5. Angular receives an **ID token** (user identity, for the app) and an **access token** (used to call the Spring Boot API).
6. Access token is sent as `Authorization: Bearer <token>` on API calls.
7. Spring Boot, configured as an **OAuth2 Resource Server**, validates the JWT signature against Okta's JWKS endpoint, checks issuer/audience/expiry, and extracts scopes/claims for authorization decisions.
8. Refresh tokens (if enabled) allow silent renewal without re-prompting login.

### Q14. How do you implement fine-grained authorization (not just authentication) using Okta claims in Spring Security?
**Answer:**
- Configure Spring Security as a resource server (`spring-boot-starter-oauth2-resource-server`), pointing `issuer-uri` at Okta's authorization server metadata endpoint.
- Map Okta **custom claims** or **groups** (e.g., `groups: ["ROLE_ADMIN", "ROLE_USER"]`) into Spring `GrantedAuthority` objects via a custom `JwtAuthenticationConverter`.
- Use method-level security (`@PreAuthorize("hasRole('ADMIN')")`) or `SecurityFilterChain` matchers (`.requestMatchers("/admin/**").hasRole("ADMIN")`) for endpoint-level authorization.
- For finer control (e.g., "can only edit your own resource"), combine role checks with a service-layer ownership check rather than relying purely on JWT claims.

### Q15. What are the key AWS services you'd use for CI/CD and IaC, and how do they fit together?
**Answer:**
- **IaC**: Terraform (most common cross-cloud choice) or **CloudFormation**/**CDK** (AWS-native, CDK lets you write infra in Java/TypeScript). Defines VPCs, subnets, security groups, ECS/EKS clusters, RDS instances, IAM roles — all version-controlled and peer-reviewed like application code.
- **CI/CD**: Jenkins, GitHub Actions, or **CodePipeline/CodeBuild/CodeDeploy** natively. Pipeline stages: build → unit test → static analysis (SonarQube) → build container image → push to **ECR** → deploy to ECS/EKS (blue-green or rolling) → smoke tests.
- **Monitoring**: **CloudWatch** (logs, metrics, alarms), **CloudWatch Container Insights** for ECS/EKS, **X-Ray** for tracing, often paired with **Grafana/Prometheus** on EKS, or third-party APM (Datadog/New Relic).
- **Secrets**: Secrets Manager or Parameter Store injected into ECS task definitions/EKS pods at runtime — never baked into images.

### Q16. Explain the difference between IAM roles, policies, and how you'd apply least-privilege for an ECS task talking to RDS and S3.
**Answer:** An **IAM policy** is a JSON document defining allowed/denied actions on resources. An **IAM role** is an identity that can be *assumed* (by an EC2 instance, ECS task, Lambda, etc.) and has policies attached — no long-lived access keys involved. For an ECS task:
- Create a dedicated **Task Role** (not the Task Execution Role, which is for pulling images/writing logs) scoped to exactly what the app needs: e.g., `s3:GetObject`/`s3:PutObject` on a specific bucket ARN, and RDS access is typically via network (security group) + DB credentials from Secrets Manager rather than IAM directly (unless using IAM database authentication).
- Avoid wildcard resources (`"Resource": "*"`) — scope to specific ARNs.
- Use separate roles per environment/service so a compromised task can't reach unrelated resources.

---

## Part 4: Spring Boot / Jakarta EE + Secure REST API Design

### Q17. How does Spring Boot auto-configuration work under the hood?
**Answer:** Spring Boot scans `META-INF/spring.factories` (or, since Spring Boot 2.7+/3.x, `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`) for `@Configuration` classes annotated `@AutoConfiguration`. Each is guarded by conditional annotations like `@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@ConditionalOnProperty` — so Spring only activates a configuration if the relevant classes are on the classpath and the developer hasn't already defined their own bean. This is why adding `spring-boot-starter-data-jpa` automatically configures a `DataSource`, `EntityManagerFactory`, and `TransactionManager` with sensible defaults you can override.

### Q18. What's the difference between Spring Boot and Jakarta EE (formerly Java EE)? Why would a project use one over the other?
**Answer:** Jakarta EE is a **specification** (Servlets, JAX-RS, CDI, JPA, Bean Validation) implemented by application servers (WildFly, Payara, Open Liberty). Spring Boot is a **framework** built largely on top of (or alongside) some of these specs (it uses JPA under the hood, and can run on embedded Tomcat/Jetty/Undertow rather than a full app server). Spring Boot favors convention-over-configuration, embedded servers, and a huge ecosystem (Spring Cloud, Spring Security, Spring Data). Jakarta EE shops tend to be legacy enterprise environments with existing app-server infrastructure, or organizations wanting to avoid vendor lock-in to Spring's opinions. Many modern greenfield projects choose Spring Boot for velocity and community support; Jakarta EE remains relevant in enterprises with existing WebSphere/WildFly investments.

### Q19. Design a versioning strategy for a REST API. What are the trade-offs between URI versioning, header versioning, and content negotiation?
**Answer:**
- **URI versioning** (`/api/v1/orders`): simplest, most visible, easy to test/cache, but "pollutes" the URI and implies the resource itself changed rather than its representation. Most widely adopted in practice for its simplicity.
- **Header versioning** (custom header like `X-API-Version: 2` or `Accept-Version`): keeps URIs clean/stable, but harder to discover/test manually (can't just hit a URL in a browser), and caching proxies may not vary on custom headers by default.
- **Content negotiation / media-type versioning** (`Accept: application/vnd.myapp.v2+json`): most RESTfully "pure" (versions the representation, not the resource), but adds complexity for clients and tooling.

**Recommendation to state in an interview:** URI versioning for major/breaking changes (`v1`, `v2`) combined with backward-compatible additive changes (new optional fields) *without* bumping the version — reserve version bumps for actual breaking changes, and maintain a deprecation policy (e.g., support N-1 version for 6-12 months with sunset headers).

### Q20. How do you design pagination for a large collection endpoint, and what are offset vs cursor-based pagination trade-offs?
**Answer:**
- **Offset/limit pagination** (`?page=3&size=20` or `?offset=40&limit=20`): simple to implement, works well for UI page numbers, but has two problems at scale: (1) performance degrades on large offsets because the DB still has to scan/skip rows, and (2) results can shift if rows are inserted/deleted between requests ("page drift").
- **Cursor/keyset pagination** (`?after=<opaque_cursor>&limit=20`, cursor typically encodes the last seen sort key, e.g., `id` or `created_at`): scales much better (uses an indexed `WHERE id > :lastId ORDER BY id LIMIT :n` instead of `OFFSET`), and is stable under concurrent inserts/deletes. Downside: can't jump to an arbitrary page number, only "next"/"previous."

**Response contract:** include metadata like `nextCursor`, `hasMore`, or `totalCount` (if cheap to compute) in a wrapper object or `Link` headers (GitHub API style: `Link: <url>; rel="next"`). For large-scale systems, prefer cursor-based; for admin UIs needing "jump to page 5," offset is often acceptable with a capped max page depth.

### Q21. Design a consistent error-handling/response format for a REST API in Spring Boot.
**Answer:** Centralize via `@RestControllerAdvice` + `@ExceptionHandler` methods, mapping exceptions to a standard error body — ideally following **RFC 7807 (Problem Details for HTTP APIs)**:
```json
{
  "type": "https://api.example.com/errors/validation",
  "title": "Validation Failed",
  "status": 400,
  "detail": "Field 'email' must be a valid email address",
  "instance": "/api/v1/users",
  "errors": [
    { "field": "email", "message": "must be a valid email address" }
  ],
  "traceId": "abc-123"
}
```
Key points to raise:
- Never leak stack traces or internal exception messages to clients in production; log full details server-side keyed by a `traceId`/correlation ID returned to the client for support lookups.
- Map exceptions consistently: validation errors → 400, auth failures → 401/403, not found → 404, conflict (e.g., optimistic locking / duplicate) → 409, unhandled → 500 (generic message only).
- Use Spring's `ResponseEntityExceptionHandler` as a base for extending built-in Spring MVC exception handling (e.g., `MethodArgumentNotValidException` from `@Valid`).

### Q22. How do you secure a REST API end-to-end (beyond just authentication)?
**Answer:**
- **TLS everywhere** (HTTPS only, HSTS headers).
- **AuthN via OAuth2/OIDC** (e.g., Okta) — Spring Security as resource server validating JWTs.
- **AuthZ**: role/scope-based access control at the endpoint and method level; never trust client-supplied IDs without ownership checks.
- **Input validation**: Bean Validation (`@Valid`, `@NotNull`, `@Size`, custom validators) to reject malformed input before it reaches business logic — reduces injection risk and bad-data bugs.
- **Rate limiting/throttling** (API Gateway, Bucket4j, or a service mesh) to prevent abuse.
- **CORS** configured explicitly (never `*` with credentials) for the specific SPA origin(s).
- **Security headers**: `Content-Security-Policy`, `X-Content-Type-Options: nosniff`, `X-Frame-Options`.
- **Dependency scanning** (OWASP Dependency-Check/Snyk) in CI, and secrets never committed to source (use Secrets Manager/Vault).
- **Audit logging** for sensitive operations, and idempotency keys for POST operations that must not be retried destructively.

### Q23. How do you handle idempotency in REST API design, particularly for POST requests (e.g., payment creation)?
**Answer:** POST is not naturally idempotent, but for operations like payments/orders you often need "at-most-once" semantics under retries (client timeout + retry). Standard approach: require an `Idempotency-Key` header from the client (a UUID generated once per logical operation). Server stores the key with the resulting response for a TTL window; if the same key arrives again, return the cached response instead of re-executing the operation. This is commonly backed by a unique constraint in the DB or a Redis cache with TTL.

---

## Part 5: Oracle/PostgreSQL — Advanced SQL & Performance Tuning

### Q24. How do you approach diagnosing a slow SQL query in production?
**Answer:**
1. Get the **execution plan**: `EXPLAIN ANALYZE` (PostgreSQL) or `EXPLAIN PLAN FOR ... ; SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY)` (Oracle).
2. Look for **full table scans** on large tables where an index scan is expected, **nested loop joins** on large row counts (should often be hash/merge joins), and **row estimate vs actual mismatches** (a sign statistics are stale).
3. Check if statistics are up to date (`ANALYZE` in Postgres, `DBMS_STATS.GATHER_TABLE_STATS` in Oracle) — stale stats cause the optimizer to pick bad plans.
4. Check for **missing or unused indexes**, and whether a composite index's column order matches the query's filter/sort pattern.
5. Look at **lock contention/wait events** (`pg_stat_activity`, Oracle's `v$session`/AWR reports) to rule out blocking rather than pure query cost.
6. Consider **query rewrite**: replacing correlated subqueries with joins/CTEs, avoiding `SELECT *`, avoiding functions on indexed columns in `WHERE` clauses (which prevent index usage — "sargability").

### Q25. Explain indexing strategy: B-tree vs composite indexes vs covering indexes, and when an index can hurt rather than help.
**Answer:**
- **B-tree index** (default in both Oracle and Postgres): good for equality and range queries (`=`, `<`, `BETWEEN`, `ORDER BY`).
- **Composite index** (multiple columns): column order matters — put the most selective/most frequently filtered-by-equality column first, then range columns last (a range condition on a non-last column stops the index from being used efficiently for subsequent columns).
- **Covering index**: includes all columns needed by a query (via `INCLUDE` in Postgres, or just adding columns to the index) so the DB can satisfy the query from the index alone without a "heap fetch"/table lookup — big win for read-heavy queries.
- **When indexes hurt**: every index adds write overhead (insert/update/delete must maintain it) and storage; over-indexing a write-heavy table can significantly slow writes. Also, on low-cardinality columns (e.g., a boolean flag), a plain B-tree index often isn't selective enough for the optimizer to use it — a partial index or bitmap index (Oracle) may be better.

### Q26. Write a query (conceptually) to find the second-highest salary per department, and explain the window function approach vs a correlated subquery.
**Answer:** Window function approach (preferred — more readable and typically more efficient):
```sql
SELECT dept_id, employee_id, salary
FROM (
  SELECT dept_id, employee_id, salary,
         DENSE_RANK() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS rnk
  FROM employees
) ranked
WHERE rnk = 2;
```
`DENSE_RANK()` handles ties correctly (two employees tied for highest both get rank 1, next distinct salary gets rank 2) — `ROW_NUMBER()` would arbitrarily split ties, which is usually wrong for "Nth highest" semantics.

**Correlated subquery alternative** (older style, often less efficient since it can re-scan per row):
```sql
SELECT e1.dept_id, e1.employee_id, e1.salary
FROM employees e1
WHERE 1 = (
  SELECT COUNT(DISTINCT e2.salary)
  FROM employees e2
  WHERE e2.dept_id = e1.dept_id AND e2.salary > e1.salary
);
```
Window functions are generally preferred in modern SQL for this class of problem — one pass over sorted partitions rather than repeated correlated evaluation.

### Q27. Explain isolation levels and how you'd choose one for a financial transaction system.
**Answer:** Standard SQL isolation levels (weakest → strongest): **Read Uncommitted**, **Read Committed**, **Repeatable Read**, **Serializable**.
- **Read Committed** (Postgres default, Oracle default): each statement sees a snapshot as of its own start; prevents dirty reads but allows non-repeatable reads/phantom reads.
- **Repeatable Read**: same snapshot for the whole transaction; prevents non-repeatable reads. Postgres implements this via MVCC without traditional locking overhead but can raise serialization errors on write conflicts.
- **Serializable**: strongest, guarantees the outcome is equivalent to *some* serial execution of transactions; needed for things like enforcing complex business invariants across multiple rows (e.g., "total allocated funds never exceed budget"), but has a real throughput cost and requires retry logic on serialization failures.

For a financial system, I'd typically use **Read Committed with explicit row-level locking (`SELECT ... FOR UPDATE`)** for hot paths like balance updates (pessimistic locking to avoid lost updates), reserving full **Serializable** for specific invariant-critical transactions where the retry cost is acceptable — full Serializable everywhere is often too costly for a high-throughput system.

### Q28. How do you handle schema migrations safely in a production database with zero downtime?
**Answer:**
- Use a migration tool (Flyway or Liquibase) versioned alongside the app, run as part of the deploy pipeline.
- **Additive-first approach**: add new nullable columns/tables first, deploy code that can read/write both old and new shape (expand), backfill data, switch reads to the new shape, then in a later release drop the old column (contract) — this "expand/contract" pattern avoids breaking currently-running instances during rolling deploys.
- Avoid long-locking DDL on large tables in production (e.g., adding a `NOT NULL` column with a default on a huge table can lock it in older Postgres versions — mitigate with `ADD COLUMN ... DEFAULT` optimizations in newer Postgres, or add nullable + backfill + add constraint separately).
- Always test migrations against a production-sized copy/staging environment first, and have a rollback script.

### Q29. What's the difference between a materialized view and a regular view, and when would you use one for performance?
**Answer:** A regular **view** is just a stored query — executed fresh every time it's referenced, no storage of results. A **materialized view** physically stores the query result and must be explicitly refreshed (`REFRESH MATERIALIZED VIEW` in Postgres, or Oracle's fast/complete refresh with a refresh group). Use materialized views for expensive aggregations/joins (e.g., a nightly reporting dashboard) queried frequently but where slightly-stale data is acceptable — trades storage and refresh overhead for read speed. Oracle additionally supports **query rewrite** with materialized views, where the optimizer automatically redirects a query against base tables to use the materialized view if it's valid and fresher — a powerful, transparent performance tool.

---

## Part 6: AWS Delivery Practices — IaC, CI/CD, Monitoring (Deeper Dive)

### Q30. Compare Terraform vs CloudFormation/CDK — which would you pick and why?
**Answer:** **CloudFormation**: AWS-native, no state file to manage (AWS manages state), tightly integrated with AWS service rollout, but YAML/JSON verbosity and slower to add new service support historically. **CDK**: lets you define infrastructure in real code (Java/TypeScript/Python), which is great for Java shops (type safety, reusable constructs, unit-testable infra) — compiles down to CloudFormation. **Terraform**: cloud-agnostic (works across AWS/Azure/GCP), huge community module ecosystem, mature state management (though state file handling — remote state in S3 + DynamoDB locking — needs to be set up carefully), HCL is easy to read. For a single-cloud AWS shop with a Java-heavy team, **CDK** is attractive for code reuse and type safety; for multi-cloud or where the org already has Terraform expertise, **Terraform** remains the industry default. I'd pick based on existing team skill and whether multi-cloud portability matters.

### Q31. Describe a CI/CD pipeline for a Spring Boot service deploying to ECS, including how you'd do safe rollouts.
**Answer:**
1. **Build stage**: `mvn/gradle build`, run unit tests, static analysis (SonarQube), dependency vulnerability scan.
2. **Package**: build a Docker image, tag with the commit SHA (never `latest` for traceability), push to **ECR**.
3. **Deploy stage**: update the ECS task definition with the new image tag, deploy via **CodeDeploy blue/green** (or ECS rolling update) — blue/green spins up the new task set alongside the old, runs health checks/smoke tests against it, then shifts traffic (optionally with a canary percentage first), keeping the old task set around briefly for instant rollback if CloudWatch alarms fire.
4. **Post-deploy**: automated smoke tests hit key endpoints; if they fail, automatic rollback triggers.
5. Gate promotion between environments (dev → staging → prod) with manual approval steps or automated test-suite gates.

### Q32. What would you monitor for a production Spring Boot service on AWS, and how do you set up alerting that isn't noisy?
**Answer:**
- **Golden signals**: latency (p50/p95/p99), traffic (request rate), errors (4xx/5xx rate), saturation (CPU/memory/thread pool/connection pool usage) — expose via Spring Boot Actuator + Micrometer, scraped into CloudWatch or Prometheus.
- **JVM metrics**: heap usage, GC pause time/frequency, thread count — catch memory leaks or GC thrashing before they cause outages.
- **DB connection pool** (HikariCP) metrics: active/idle connections, wait time — a leading indicator of DB-side slowness.
- **Distributed tracing** (X-Ray or OpenTelemetry) to see cross-service latency breakdowns.
- **Alerting design**: alert on symptom (elevated error rate, latency SLO breach) rather than every individual cause; use multi-window burn-rate alerts (e.g., SRE-style: fast burn over 5 min AND slower burn over 1 hr) to avoid paging on transient blips while still catching real incidents quickly. Route low-urgency anomalies to a dashboard/ticket instead of paging.

### Q33. How do you manage secrets and configuration differences across dev/staging/prod environments in AWS?
**Answer:** Store non-sensitive config in **Parameter Store** (SSM) organized by hierarchy (`/myapp/prod/db-url`), sensitive values (passwords, API keys) in **Secrets Manager** (supports automatic rotation, e.g., for RDS credentials). Spring Boot picks these up via `spring-cloud-aws` config import or at container startup via environment variables injected by the ECS task definition (referencing the Secrets Manager ARN, so the secret is never in plaintext in the task definition or in source control). Keep environment-specific `application-{profile}.yml` files for non-secret, non-sensitive differences, and use **Spring Profiles** to activate the right one per environment.

---

## How to Use This
Given the breadth, I'd suggest picking 2-3 questions per section to actually rehearse out loud (not just read) — especially Q13/Q14 (Okta/OAuth2 flow), Q20/Q21 (pagination/error handling), Q26/Q27 (SQL), and Q31/Q32 (CI/CD + monitoring), since these tend to come up as live whiteboard/verbal-design questions rather than pure recall.
