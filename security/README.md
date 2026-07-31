# Module 6 — Security (Spring Security, JWT, OAuth2/OIDC, SAML, OKTA, SCIM, MFA, RBAC, CORS, CSRF)

**Domain used throughout:** the same Order/Inventory system from Module 1
(`java-basics/`) — `Customer`, `Product`, `Order`, `Inventory`. This module
secures that domain's REST surface conceptually: who can place an order
(any authenticated customer), who can restock inventory (managers only), and
what protects the API layer that Module 5 (`spring/`) builds around it. This
module's code is self-contained (it does not import Module 5's classes — see
"Scope & integration" below) but every design decision explicitly says how it
plugs into that REST layer.

Companion files:
- [diagrams/jwt-request-validation-flow.md](diagrams/jwt-request-validation-flow.md) — login + protected-request sequence diagrams
- [diagrams/oauth2-oidc-pkce-flow.md](diagrams/oauth2-oidc-pkce-flow.md) — Authorization Code + PKCE flow against an external IdP (OKTA)
- [src/](src/) — the actual code (`com.interviewprep.orders.security`)
- [EXPLANATION.md](EXPLANATION.md) — line-by-line walkthrough of every file in `src/`
- [EXERCISES.md](EXERCISES.md) — hands-on exercises
- [INTERVIEW.md](INTERVIEW.md) — beginner/intermediate/senior/scenario interview questions with ideal answers

## Scope & integration with Module 5 (`spring/`)

Module 5, built concurrently with this module, adds the REST controllers
(`OrderController`, `InventoryController`, ...) over Module 1's domain. This
module's `SecurityConfig`, `JwtAuthenticationFilter`, and
`SecuredInventoryOperations` are written as if they sit in that same Spring
Boot application, securing those exact endpoints — the `@PreAuthorize`
example operates on a stock-restock-equivalent operation and a
customer-facing read operation precisely because those are the two
operations Module 5's `InventoryController` exposes. In a real merge of the
two modules you would delete this module's standalone `pom.xml` and
`SecurityDemoApplication`, and fold `com.interviewprep.orders.security`'s
classes into the single application Module 5 defines. They are kept as two
separate, independently-buildable Maven modules here only so each can be
built and reviewed in isolation while both are under active development.

## Build & run (once merged, or standalone as written)

```bash
# Standalone, as this module ships it:
cd security
mvn spring-boot:run
# -> starts on :8081 (see application.yml)

curl -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}'
# -> { "accessToken": "...", "tokenType": "Bearer", "expiresInSeconds": 900 }

curl http://localhost:8081/actuator/health
# -> 200, no token required (permitAll())

# Using the token against a protected endpoint (once merged with Module 5's
# controllers; this module ships no /api/v1/inventory endpoint itself):
curl -X POST http://localhost:8081/api/v1/inventory/restock \
  -H "Authorization: Bearer <accessToken from login>"
```

This sandbox has no JDK/Maven installed, so none of the above has actually
been executed here — every file has been written and re-read carefully for
correctness, but verify by running it yourself.

---

## 1. JWT (JSON Web Tokens)

### What it is
A JWT is a compact, URL-safe string with three base64url-encoded, dot-separated
parts: `header.payload.signature`. The header names the signing algorithm and
token type; the payload is a JSON object of **claims** (`sub`, `iss`, `exp`,
custom claims like this module's `roles`); the signature proves the header +
payload haven't been altered since a trusted party (holding the signing key)
produced them. Crucially: the payload is **encoded, not encrypted** — anyone
can base64-decode and read it. A JWT proves *integrity and authenticity*, not
*confidentiality*.

### Why introduced / problem it solves
Before token-based auth became dominant, the default web auth pattern was a
server-side session: log in, server stores session state (user id, roles) in
memory or a shared store (Redis), and hands the client an opaque session ID
cookie. Every request needs a lookup against that store to know who's
calling. That's fine for a single server or a small cluster with a shared
session store, but it couples every request to session-store availability
and adds a network hop (or shared-cache dependency) to every authenticated
request. JWTs solve this by making the token **self-describing**: everything
needed to authenticate (and, via custom claims, partially authorize) a
request travels inside the token itself, verifiable with nothing but a
signing key already held by the verifier — no database or cache round-trip
required just to authenticate.

### Problem it solves in a microservices context specifically
In a single monolith, "is this session valid" is one lookup against one
store. Across a dozen microservices, either every service needs access to
that same shared session store (a real coupling and availability risk — if
the session store degrades, *every* service's auth breaks), or each service
needs to make an auth-check network call to a central auth service on every
request (added latency, added single point of failure). A JWT, especially
RS256-signed, lets every service verify a caller's identity and claims
**locally**, using a public key it already has cached, with zero network
calls and zero shared infrastructure dependency at request time.

### When to use / when NOT to use
- **Use** for stateless API authentication/authorization across services,
  especially where horizontal scaling and independent service deployability
  matter more than instant revocation.
- **Use** short-lived access tokens (minutes) plus a longer-lived refresh
  token issued and validated only against a dedicated, storeful auth
  endpoint — this bounds the "can't revoke a JWT early" problem to the
  refresh token's validity window, not the (much longer) session lifetime a
  user actually experiences.
- **Don't** use a JWT as a substitute for server-side session state that
  needs to change moment-to-moment (e.g. "how many items are in this user's
  cart right now") — that's mutable application state, not an identity
  claim, and belongs in a database/cache, not re-encoded into a new token on
  every change.
- **Don't** put anything sensitive (passwords obviously, but also full PII,
  internal IDs meant to stay internal) in the payload — see "encoded, not
  encrypted" above.
- **Don't** reach for JWTs reflexively for a simple server-rendered
  session-cookie web app with no cross-service or mobile-client requirement —
  a plain server-side session is simpler, has trivial instant revocation
  (delete the session), and doesn't carry JWT's revocation trade-off at all.
  JWTs solve a *scaling/decoupling* problem; if you don't have that problem
  yet, you're taking on complexity (algorithm choice, key management, clock
  skew, revocation strategy) for no benefit.

### Trade-offs & performance implications
- **The central trade-off: statelessness vs. revocability.** A traditional
  session can be invalidated instantly (delete the row/cache entry) — "log
  out everywhere" or "an admin disables a compromised account" takes effect
  on the very next request. A stateless JWT, by design, cannot be
  invalidated before its `exp` without reintroducing server-side state (a
  denylist of revoked token IDs, checked on every request — which
  reintroduces exactly the shared-store coupling JWTs were meant to avoid,
  just for a smaller/append-mostly data set). The practical mitigation
  virtually every real system uses: short-lived access tokens (5–15 minutes,
  as this module's `JwtProperties` defaults to) plus a refresh-token flow, so
  "revoke access" means "stop honoring the refresh token" and the blast
  radius of *not* being able to kill an already-issued access token
  immediately is capped at its short TTL.
- **Size.** A JWT with several claims is meaningfully larger than an opaque
  session ID cookie — sent on every request. Rarely matters versus typical
  payload sizes, but is a real, measurable cost at extreme request volumes.
- **Verification cost.** HMAC (HS256) verification is cheap (a keyed hash).
  RSA (RS256) verification is more expensive per call than HMAC but still
  fast relative to typical request latency (network, DB, business logic) —
  see `JwtService`'s Javadoc for the full HS256-vs-RS256 discussion.

### Enterprise examples
- A payments platform issuing short-lived (2–5 minute) JWTs scoped narrowly
  (`payments:write` audience only) for a single transaction's lifetime,
  rather than reusing the same broad session token across every internal
  call.
- A company running dozens of microservices behind an API gateway that
  terminates OIDC/OAuth2 against OKTA once, then mints or forwards a JWT that
  every downstream service verifies independently using a cached, shared
  JWKS — no service-to-service call back to the gateway or IdP needed per
  request.

### Common mistakes
See the dedicated [§11 Common Token-Validation Mistakes](#11-common-token-validation-mistakes)
section below — this topic is large enough, and interview-relevant enough,
to warrant its own section rather than a short bullet list here.

---

## 2. Spring Security Filter Chain, Stateless Sessions, Public vs. Protected Endpoints

### What it is
Spring Security intercepts every HTTP request through a chain of
`Filter`s before it ever reaches a `@RestController`. `SecurityConfig`
(`SecurityFilterChain` bean) declares: which URL patterns are public
(`permitAll()`), which require authentication (`anyRequest().authenticated()`),
the session policy (`STATELESS` here — no `HttpSession` is created or
consulted for auth), and where this module's custom
`JwtAuthenticationFilter` slots into that chain
(`addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)`).

### Why introduced / problem it solves
Without a centralized filter-chain-based model, "is this request allowed"
logic tends to get reimplemented — inconsistently — inside individual
controller methods (`if (user == null) return 401;` scattered everywhere).
Centralizing it means one place declares the security posture of the entire
application, it's enforced *before* any business logic runs (a request that
fails authorization never executes a single line of a controller method),
and it's testable and auditable as a single unit.

### When to use / when NOT to use
- Use `SessionCreationPolicy.STATELESS` for any API where JWTs (self-issued
  or IdP-issued) are the sole auth mechanism — there's no reason to also pay
  for session creation/lookup machinery you never use.
- Don't set `STATELESS` if the same application also serves a traditional
  server-rendered login flow that genuinely needs `HttpSession` (e.g. a mixed
  app with both a JSON API and a classic web UI) — `SessionCreationPolicy.IF_REQUIRED`
  is the right default there, and you'd likely split these into two distinct
  `SecurityFilterChain` beans scoped to different URL patterns rather than
  force one policy on both.

### Trade-offs & performance implications
- Stateless sessions remove a class of infrastructure entirely (no session
  store, no sticky-session load-balancer configuration) at the cost of the
  revocability trade-off already discussed in §1.
- Every filter in the chain runs on every matching request — keep custom
  filters (like `JwtAuthenticationFilter`) cheap; the `shouldNotFilter`
  override for the login path is a small example of avoiding unnecessary
  work on requests that can't possibly need it.

### Enterprise examples
Virtually every modern API gateway / backend-for-frontend architecture at a
bank or large tech company runs its APIs `STATELESS`, with authentication
delegated to a JWT (often IdP-issued, per §6) validated per-request with no
session affinity requirement — this is precisely what lets such systems
scale horizontally behind a plain round-robin load balancer.

### Common mistakes
- Leaving Spring Security's default `formLogin()`/`httpBasic()` enabled on a
  JSON API "because it was on by default" — surprises API consumers with an
  unexpected login-page redirect or Basic-auth browser prompt on a 401
  instead of a clean JSON error body (this module explicitly disables both —
  see `SecurityConfig`).
- Encoding fine-grained, frequently-changing authorization rules entirely as
  URL-pattern matchers in `authorizeHttpRequests(...)`, producing an
  unreadable, hard-to-audit wall of `requestMatchers(...)` rules instead of
  pushing role-specific logic down to method-level `@PreAuthorize` (§5) where
  it belongs.

---

## 3. CSRF (Cross-Site Request Forgery)

### What it is
An attack where a victim's browser is tricked into sending a request to a
site the victim is already authenticated to, using credentials the browser
attaches **automatically and invisibly** — classically, a session cookie —
without the victim's knowledge or an attacker ever seeing that credential
directly. The attacker's page can't *read* the response (same-origin
policy/CORS block that), but doesn't need to — the side effect of the forged
request (change email, transfer funds, place an order) already happened.

### Why this module disables it — precisely
This API is `STATELESS` and authenticates exclusively via a Bearer token in
the `Authorization` header (never a cookie). CSRF specifically exploits
**ambient credentials** — ones the browser attaches to a request without the
requesting page having to know or supply them. Cookies are ambient by
design (`Set-Cookie` once, then the browser attaches them to every matching
request automatically, cross-origin forms included, unless `SameSite`
restricts it). A custom header like `Authorization: Bearer <token>` is
**not** ambient — a browser has no built-in mechanism to attach an arbitrary
header to a request initiated by a third-party page, and that third-party
page's JavaScript has no access to read the victim's token in the first
place (it's not sitting in a cookie jar or any other browser-managed,
cross-origin-readable store). With no ambient credential for a forged
cross-site request to ride on, the CSRF attack has no mechanism to succeed
against this specific endpoint shape — which is exactly why
`SecurityConfig` disables CSRF protection with `.csrf(AbstractHttpConfigurer::disable)`,
and exactly why Spring Security's own reference documentation recommends
doing so for stateless, non-cookie APIs.

### When CSRF protection is still needed — precisely
The moment **any** cookie-based credential re-enters the picture — a
classic server-side session, a "remember me" cookie, or (a genuinely common,
reasonable pattern) storing a **refresh token** in an `httpOnly` cookie
instead of `localStorage` for better XSS resistance (§11) — CSRF protection
must be re-enabled for whatever endpoints accept that cookie. The presence
of JWTs *elsewhere* in the same system does not make CSRF protection
unnecessary; the determining factor is strictly "does *this* endpoint accept
an ambient, browser-auto-attached credential," evaluated endpoint by
endpoint, not architecture by architecture. A system that authenticates
*most* endpoints via Bearer tokens but has one `/refresh` endpoint reading an
`httpOnly` cookie needs CSRF protection on that one endpoint specifically
(commonly: Spring Security's `CookieCsrfTokenRepository`, or requiring the
SPA to also send a custom header alongside the cookie that a forging page
can't replicate — the "double-submit cookie" pattern).

### When to use / when NOT to use
- Disable CSRF protection for genuinely stateless, header-token-only APIs
  with no cookie-based credential of any kind.
- Enable (never disable) CSRF protection for any endpoint a browser will
  submit using an ambient, automatically-attached credential.

### Trade-offs & performance implications
Negligible performance cost either way at typical request volumes — this is
purely a correctness/threat-model decision, not a performance one.

### Enterprise examples
A bank's mobile-first API is entirely Bearer-token/stateless (no CSRF
protection needed) but its legacy web portal, kept alive during a migration,
still issues a session cookie for backward compatibility — that portal's
form-submission endpoints retain full CSRF token protection while the new
mobile API does not, correctly reflecting that they have different threat
models despite living in the same organization.

### Common mistakes
- Disabling CSRF protection everywhere as a boilerplate first step when
  setting up Spring Security, without checking whether the app also uses
  cookies anywhere (very easy to do since Spring Security's tutorials often
  show `.csrf(AbstractHttpConfigurer::disable)` for API examples without
  restating the precondition).
- The inverse mistake: leaving default CSRF protection enabled on a truly
  stateless JSON API and being confused why non-browser clients (mobile
  apps, `curl`, service-to-service calls) fail with 403 — CSRF tokens are a
  browser-form concept and make no sense to demand from a client that never
  has an ambient cookie to protect in the first place.

---

## 4. CORS (Cross-Origin Resource Sharing)

### What it is
A **browser-enforced** mechanism (not a server-side security boundary by
itself) that relaxes the same-origin policy in a controlled way: the server
declares, via `Access-Control-Allow-*` response headers, which origins,
methods, and headers a cross-origin browser script may use to call it and
read the response.

### Why introduced / problem it solves
The same-origin policy exists to stop `evil.com`'s JavaScript, running in a
victim's browser, from reading responses from `bank.com` using the victim's
ambient cookies. That's the right default, but plenty of *legitimate*
architectures are cross-origin by design (`app.frontend.com` calling
`api.backend.com`) — CORS lets a server opt specific origins back in, on its
own terms, instead of same-origin policy being all-or-nothing.

### This module's configuration (`CorsConfig`)
Explicit allow-list of origins (`http://localhost:4200` for the Angular dev
server, a placeholder prod origin), explicit allowed methods and headers
(notably `Authorization`, which is not a CORS "simple" header and must be
listed or browser preflight `OPTIONS` requests fail before the real request
is ever sent), and `allowCredentials(false)` — correct here because this API
never asks a browser to send cookies cross-origin.

### The dangerous combination to avoid: wildcard origin + credentials
`Access-Control-Allow-Origin: *` together with
`Access-Control-Allow-Credentials: true` is rejected outright by the CORS
spec/every modern browser as a literal combination — so this exact
misconfiguration usually just breaks credentialed requests rather than
creating a live vulnerability. The **real, exploitable** version of the same
mistake is subtler and passes browser validation: **reflecting whatever
`Origin` header a request sent** back as the literal
`Access-Control-Allow-Origin` value (instead of checking it against a fixed
allow-list), combined with `allowCredentials(true)`. Because the header
value isn't the literal string `"*"`, browsers accept it — but functionally,
*any* origin can now make credentialed cross-origin requests and read
responses, identical in effect to a true wildcard-with-credentials. This
exact "fixed a CORS error by reflecting the origin" pattern has caused real
production account-takeover-class vulnerabilities.

### When to use / when NOT to use
- Configure CORS whenever a browser-based frontend is served from a
  different origin (different scheme/host/port) than the API — essentially
  always true for an Angular SPA talking to a separately-deployed Spring
  Boot API.
- Never use a wildcard origin (or origin-reflection) together with
  `allowCredentials(true)`. If you don't need cookies cross-origin (this
  module doesn't), keep `allowCredentials(false)` as an extra layer of
  protection even though the allow-list should already prevent abuse.

### Trade-offs & performance implications
Preflight (`OPTIONS`) requests add one extra round-trip for "non-simple"
requests (custom headers like `Authorization`, non-simple content types).
`CorsConfiguration.setMaxAge(...)` lets the browser cache a preflight
decision to amortize this cost across repeated calls to the same endpoint
shape.

### Enterprise examples
A large e-commerce platform serving its checkout SPA from
`checkout.retailer.com` and its APIs from `api.retailer.com` maintains a
short, explicit, code-reviewed allow-list of exactly which frontend origins
(including staging/QA subdomains) may call which API origins — CORS
configuration changes are treated as a security-relevant change requiring
review, not a quick unblock applied by whoever hit the error first.

### Common mistakes
- "Fixing" a CORS error in development by setting the origin to `*` (or
  reflecting the request's `Origin` header) and never revisiting it before
  shipping to production.
- Forgetting to include a custom header the frontend actually sends (like
  `Authorization`) in `allowedHeaders`, causing preflight failures that
  manifest confusingly as "the API call never even happens" in browser
  devtools' network tab.
- Assuming CORS is an authorization mechanism — it says nothing about
  *which user* can do *what*; it only controls whether a *browser* will let
  cross-origin JavaScript see a response. A same-origin `curl` request, or a
  browser request from an allowed origin made by an unauthenticated or
  under-privileged user, is entirely unaffected by CORS and must still be
  rejected by real authentication/authorization (§1, §5).

---

## 5. RBAC (Role-Based Access Control) & Method Security

### What it is
Authorization decisions based on a caller's assigned role(s) rather than
per-user, per-resource rules (that's the more granular ABAC/ACL model, out of
scope here). This module models roles as the closed `Role` enum
(`CUSTOMER`, `MANAGER`, `ADMIN`) and enforces them with Spring Security's
method security (`@PreAuthorize`), demonstrated in
`SecuredInventoryOperations`:

```java
@PreAuthorize("hasRole('MANAGER')")
public void restock(String sku, int quantity) { ... }   // manager-only, mutating

@PreAuthorize("hasAnyRole('MANAGER', 'CUSTOMER')")
public int viewStock(String sku) { ... }                 // any authenticated caller
```

### Why introduced / problem it solves
Without a declarative model, authorization checks tend to be reimplemented
as ad hoc `if (!user.getRoles().contains("MANAGER")) throw ...` scattered
through business logic — easy to forget on a new endpoint, hard to audit as
a whole ("show me every operation a CUSTOMER can perform" requires grepping
the entire codebase instead of reading one annotation per method).
`@PreAuthorize` makes the rule visible right next to the method it protects,
enforced automatically via a Spring AOP proxy before the method body ever
runs.

### `hasRole` vs `hasAuthority`
`hasRole('MANAGER')` is sugar for `hasAuthority('ROLE_MANAGER')` — Spring
Security silently prepends `ROLE_`. `AppUserPrincipal.getAuthorities()`
always emits the `ROLE_`-prefixed form specifically so `hasRole(...)` works
consistently everywhere in this codebase; mixing `hasRole` and unprefixed
`hasAuthority` checks in the same codebase is a common, confusing bug (the
unprefixed one silently never matches).

### When to use / when NOT to use
- Use RBAC when permissions genuinely cluster around a small number of job
  functions (customer vs. manager vs. admin) — the common case for internal
  tooling and most CRUD APIs.
- Don't reach for RBAC when authorization actually depends on the specific
  resource instance, not just the caller's role (e.g. "a customer may view
  *their own* orders but not anyone else's" — that's not expressible as
  "does this role have permission," it requires comparing the caller's
  identity to the resource's owner, an ABAC-flavored check often written as
  a `@PreAuthorize("#customerId == authentication.principal.userId")`
  SpEL expression, or handled in the service layer directly). RBAC alone
  answers "can a MANAGER restock inventory," not "can *this* customer see
  *that* customer's order" — most real systems need both role checks and
  ownership checks together.
- Don't design an ever-growing flat list of narrow roles
  (`INVENTORY_VIEWER`, `INVENTORY_EDITOR`, `ORDER_VIEWER`, ...) without a
  plan — that's really permission-based access control wearing RBAC's
  clothing, and usually indicates it's time to separate "roles" (job
  functions) from "permissions" (fine-grained capabilities) as two distinct,
  many-to-many-related concepts instead of one flat enum.

### Trade-offs & performance implications
Method security adds a small AOP-proxy overhead per call (negligible versus
typical request cost). The real trade-off is design-time, not runtime: URL-level
rules (`SecurityConfig`) are easy to audit for "which endpoints are public"
at a glance but get unwieldy for fine-grained per-role rules; method-level
rules (`@PreAuthorize`) scale better for fine-grained rules and protect a
method regardless of *how* it's invoked (HTTP, an internal batch job, a
message-queue consumer) but require scanning the whole codebase to answer
"which endpoints are public," which is why this module deliberately uses
**both**: URL-level for the coarse public/private split, method-level for
role-specific rules within the "private" set.

### Enterprise examples
A bank's internal ops console: tellers (`CUSTOMER`-equivalent scope, ability
to view accounts and process routine transactions), branch managers
(approve overrides, view branch-wide reports), and admins
(user/role management itself) — each mapped to `@PreAuthorize` rules on the
service methods those UI screens call, independent of which specific screen
or API version invokes them.

### Common mistakes
- Forgetting `@EnableMethodSecurity` — `@PreAuthorize` annotations are
  silently ignored (no error at startup, no error at call time — the method
  just runs unauthorized) if method security isn't enabled. Always assert
  this with a test expecting a 403, never assume the annotation "just works."
- Applying `@PreAuthorize` to a `private` method or a method invoked
  internally (`this.restock(...)` from another method in the *same* class) —
  Spring AOP proxies only intercept calls that go through the proxy from
  *outside* the bean; a self-invocation bypasses the proxy entirely and the
  check silently never runs.
- Checking roles only at the UI layer (hiding a "Restock" button for
  non-managers in Angular) without *also* enforcing it server-side — a
  hidden button is a UX nicety, not a security control; the API must reject
  the underlying request regardless of what the UI shows, since any client
  can call the API directly.

---

## 6. OAuth2 / OIDC

### What it is
**OAuth2** is an *authorization* framework — its core question is "can this
client access this resource on the user's behalf, with what scope."
**OpenID Connect (OIDC)** is an *identity* layer built on top of OAuth2,
adding a standardized answer to "who is this user" (the `id_token`, `userinfo`
endpoint, standardized identity claims) that OAuth2 alone never specified.
This is why real-world "log in with Google/Microsoft/OKTA" is OIDC, not bare
OAuth2 — plain OAuth2 was never designed to answer "who logged in," only "what
can this token do."

### The Authorization Code flow with PKCE
Walked through step by step, with a Mermaid sequence diagram, in
[diagrams/oauth2-oidc-pkce-flow.md](diagrams/oauth2-oidc-pkce-flow.md). Short
version: the browser is redirected to the Auth Server (OKTA) to authenticate
directly with it (the SPA never sees the password); OKTA redirects back with
a short-lived, single-use authorization code; the SPA exchanges that code
(plus a PKCE `code_verifier` proving it's the same client that started the
flow) for an `access_token` (and, for OIDC, an `id_token`); the SPA then
calls the API with `Authorization: Bearer <access_token>`.

### ID token vs. access token
Covered in full, with a comparison table, in
[diagrams/oauth2-oidc-pkce-flow.md](diagrams/oauth2-oidc-pkce-flow.md#id-token-vs-access-token--the-single-most-common-oidc-confusion).
The one-sentence version: the ID token proves identity to the *client app*
(never send it to an API); the access token proves authorization to call the
*API* (that's the one that goes in the `Authorization` header).

### Spring Security OAuth2 Resource Server — validating OKTA-issued tokens
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://dev-example.okta.com/oauth2/default
```
With this one property (plus the `spring-boot-starter-oauth2-resource-server`
dependency and `http.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))`
in the filter chain), Spring Security fetches OKTA's OIDC discovery document,
follows it to OKTA's JWKS endpoint, caches OKTA's public signing keys, and
validates every incoming token's signature/expiry/issuer automatically —
replacing this module's entire hand-rolled `JwtService`/
`JwtAuthenticationFilter` pair for the case where **an external IdP**, not
this application, issues the tokens. See
`security/src/main/resources/application-oauth2-resource-server-example.yml`
for the fully-commented example and `SecurityConfig`'s "Alternative path"
comment for exactly what code changes.

**This is explained conceptually with example configuration only** — there
is no reachable OKTA (or other IdP) tenant in this sandbox, so this has not
been exercised against a live IdP.

### When to use / when NOT to use
- Use OAuth2/OIDC federated to a real IdP whenever your organization already
  runs one (OKTA, Azure AD, Auth0, Google Workspace) — reinventing username/
  password storage and login UX (this module's `DemoUserDirectory`/
  `AuthController`) when a company-standard IdP already exists is almost
  always the wrong call in a real enterprise setting, and is a common "how
  would you do this differently" senior-interview follow-up after showing
  self-issued JWT code.
- Use the self-issued-JWT approach this module implements directly for a
  small, standalone service/demo, or for service-to-service tokens where an
  interactive login flow makes no sense at all (see the RS256 discussion in
  §1/`JwtService` — a service-to-service token still often uses a JWT, just
  never via an authorization-code/browser-redirect flow, since there's no
  human and no browser involved).
- Don't use the Implicit flow (an older OAuth2 flow that returned tokens
  directly in a redirect URL fragment) for new SPA development — it's been
  formally deprecated in OAuth 2.1 in favor of Authorization Code + PKCE
  precisely because tokens in a URL fragment are more exposed (browser
  history, referrer leakage, server logs if a proxy mishandles fragments).

### Trade-offs & performance implications
Federating to an external IdP adds a network dependency (the IdP must be
reachable for login and periodic JWKS refresh) and a small amount of
integration complexity (redirect URIs, CORS on the IdP's side, client
registration) in exchange for not owning password storage, MFA enforcement,
breach-credential-stuffing defense, or compliance scope for credentials at
all — for most enterprises this trade strongly favors federating.

### Enterprise examples
Nearly every SaaS-consuming enterprise (the target audience of this
curriculum's target companies) runs employee-facing internal tools behind
OKTA-federated SSO specifically so a single OKTA deprovisioning action
(§8, SCIM) revokes access across every connected application at once,
instead of IT having to manually disable an ex-employee's account in dozens
of separate systems.

### Common mistakes
- Treating OAuth2 alone as sufficient for "who is the user" (it isn't — that
  requires OIDC on top, or a vendor-specific extension).
- Sending the `id_token` to an API instead of the `access_token` (§ above).
- Skipping PKCE for a public client (SPA or mobile app) — mandatory in
  practice for any client that can't hold a confidential secret.

---

## 7. SAML (Security Assertion Markup Language)

### What it is
An older (2005-era), XML-based standard for exchanging authentication and
authorization data between an Identity Provider (IdP) and a Service Provider
(SP) — conceptually the same problem OIDC solves, from an earlier generation
of web architecture (heavily oriented around full-page browser redirects and
enterprise SSO, predating modern SPA/mobile-API concerns entirely).

### SP-initiated vs. IdP-initiated flow
- **SP-initiated:** the user visits the Service Provider (e.g.
  `orders.company.com`) first. The SP, seeing no valid session, redirects the
  browser to the IdP with a SAML `AuthnRequest`. The IdP authenticates the
  user (or reuses an existing IdP session — this is what makes "click one
  app, seamlessly land in another" SSO work) and redirects back to the SP
  with a signed SAML `Response` containing an assertion the SP validates
  before establishing its own session.
- **IdP-initiated:** the user starts at the IdP's own portal/dashboard (e.g.
  clicking an app tile in OKTA's home page) and is pushed directly to the SP
  with a SAML assertion already in hand, with no initial SP-side redirect.
  Slightly less secure by convention (no SP-generated `RelayState`/request
  to correlate the response against, historically a vector for certain replay
  concerns) but a very common enterprise UX for "here's your app launcher."

### How it differs from OAuth2/OIDC
| | SAML | OAuth2 / OIDC |
|---|---|---|
| **Format** | XML assertions, digitally signed (XML-DSig) | JSON tokens (JWTs, typically) |
| **Primary use case** | Enterprise browser-based SSO into web applications | API authorization (OAuth2) and modern web/mobile/SPA authentication (OIDC) |
| **Mobile/native app fit** | Poor — heavily browser/redirect-oriented, awkward on mobile | Good — Authorization Code + PKCE was designed with mobile/SPA clients in mind |
| **Token size/verbosity** | Large, verbose XML | Compact JSON/base64url |
| **Era / trajectory** | Still very much alive in large enterprises (many legacy and even current IT-provisioned SaaS integrations are SAML-only) | The default choice for new development |

### When to use / when NOT to use
- You typically don't *choose* SAML for new development — you support it
  because a customer's enterprise IT department, or a SaaS vendor you must
  integrate with, only offers SAML-based SSO. Recognizing "this vendor only
  supports SAML, not OIDC" and being able to implement against it (or via a
  library like Spring Security SAML / a dedicated SAML SP library) is the
  realistic skill being tested, not "prefer SAML."
- Use OIDC for anything greenfield, mobile, or SPA-facing.

### Trade-offs
XML processing (canonicalization, XML signature verification) is more
complex and historically more prone to parser-level vulnerabilities (XML
external entity attacks, signature-wrapping attacks) than JSON/JWT handling
— another reason new systems default to OIDC.

### Enterprise examples
A B2B SaaS product selling into large enterprises almost always ends up
needing to support SAML SSO as an enterprise-tier feature, specifically
because many large customers' internal IT policy mandates SSO via their
existing SAML-based IdP (often, historically, one that predates that
company's OKTA/Azure AD adoption) — this is a very common real feature
request ("can we get SAML SSO") at exactly the kind of company this
curriculum targets.

### Common mistakes
- Assuming "the customer wants SSO" always means OIDC — always confirm which
  protocol their IdP actually speaks before scoping the work; SAML SSO
  integration is a meaningfully different (and typically larger) engineering
  task than OIDC.
- Rolling a hand-written XML signature validator instead of using a
  battle-tested SAML library — XML signature verification has enough sharp
  edges (signature wrapping attacks in particular) that this is squarely
  "never write your own crypto/parsing" territory.

---

## 8. OKTA and SCIM

### OKTA's role
OKTA is a cloud Identity Provider (IdP) — it's where an organization's
employees (or, for a B2C/B2B product, its customers) actually authenticate,
and where roles/group memberships are centrally managed. In the
architectures this module discusses, OKTA is the thing on the other end of
§6's Authorization Code flow and the issuer named in
`spring.security.oauth2.resourceserver.jwt.issuer-uri`. It centralizes: user
directory, password policy, MFA enrollment/enforcement (§9), single sign-on
across every OIDC/SAML-connected application, and — via SCIM — automated
provisioning into those applications.

### SCIM (System for Cross-domain Identity Management)
A standardized REST/JSON protocol for **automating** user and group
lifecycle events (create, update, deactivate/delete) between an IdP and
every downstream application, instead of each application requiring a
human admin to manually create/disable accounts. An application that
implements a SCIM endpoint lets OKTA (or another SCIM-capable IdP) push
"this user was just deactivated in the company directory" directly to it in
near-real-time — no manual step.

### The interview question this answers directly: offboarding across 50 SaaS apps
Without SCIM: an employee leaves, IT must manually log into and disable that
person's account in every one of 50 separate SaaS applications — slow,
error-prone, and a real, common source of security incidents (ex-employees
retaining access to some forgotten app for weeks or months). With SCIM
provisioning wired up for all 50 apps: IT disables the employee once, in
OKTA, and OKTA's SCIM integration pushes a deactivation call to every
connected application within minutes, automatically. This exact scenario —
phrased almost exactly this way — is a standard senior-level security
interview question, precisely because it tests whether a candidate
understands *automated* identity lifecycle management as a distinct concern
from *authentication* (getting a token) — SCIM is squarely about
provisioning/deprovisioning, not about how a session or token is obtained.

### When to use / when NOT to use
- Implement a SCIM endpoint in your application if you sell to enterprise
  customers who run their own IdP and expect automated user lifecycle
  management as a baseline enterprise feature (increasingly table-stakes,
  not a nice-to-have, for B2B SaaS).
- Don't build a custom, proprietary provisioning API instead of SCIM if
  you're trying to integrate with many customers' varied IdPs — SCIM's value
  is precisely that it's a standard every major IdP already speaks; a custom
  protocol means every enterprise customer's IT team has to build one-off
  integration work instead of flipping a switch in their existing IdP.

### Trade-offs
Implementing SCIM correctly (proper attribute mapping, idempotent
create/update semantics, handling group membership changes, pagination on
large directories) is nontrivial engineering work — but the alternative
(fully manual account lifecycle management) doesn't scale past a handful of
enterprise customers and is a recurring audit/compliance finding
(SOC 2 / ISO 27001 access-review controls specifically check for timely
deprovisioning).

### Enterprise examples
Any enterprise-tier B2B SaaS product (Salesforce, Atlassian products, Slack,
etc.) supports SCIM specifically so their enterprise customers' OKTA/Azure AD
tenant can auto-provision/deprovision users into that product — a very
common integration checklist item in enterprise sales cycles at exactly the
kind of companies this curriculum targets.

### Common mistakes
- Treating SCIM and SSO (OIDC/SAML) as the same thing — they solve different
  problems (SSO: how does an already-provisioned user log in; SCIM: how does
  that user's account get created/updated/removed in the first place) and a
  real integration typically needs both, not either/or.
- Building "provisioning" as a one-time import script instead of a live,
  ongoing SCIM integration — misses every subsequent change (role change,
  deactivation) after the initial import.

---

## 9. MFA (Multi-Factor Authentication)

### What it is
Requiring a second, independent proof of identity beyond a password —
something the user *has* (a TOTP authenticator app, a hardware key) or *is*
(biometrics), not just something they *know* (a password, which can be
phished, reused across breaches, or guessed).

### TOTP vs. SMS/push
- **TOTP (Time-based One-Time Password)** — e.g. Google Authenticator,
  Authy: a shared secret is established once (typically via a QR code) between
  the server and the authenticator app; both independently compute a
  6-digit code from that secret plus the current time window (usually 30s),
  with no network communication needed at verification time. Resistant to
  SIM-swapping and network interception (there's no network step to
  intercept during the actual code generation).
- **SMS-based** — a code is texted to the user's phone number.
  Convenient (no app install) but weaker: vulnerable to SIM-swapping attacks
  (an attacker social-engineers a carrier into porting the victim's number)
  and to SMS interception at the carrier/network level. NIST has
  deprioritized SMS as an acceptable authenticator for exactly this reason
  in modern guidance, though it remains extremely widely used for its
  convenience/reach.
- **Push-based** (e.g. "Approve this login?" in the OKTA Verify or Duo
  app) — generally strong and low-friction, though vulnerable to "MFA
  fatigue" social-engineering attacks (bombarding a user with approval
  requests until one is accidentally/wearily approved) unless paired with
  number-matching (the user must enter a number shown on the login screen
  into the app, not just tap "Approve").

### Where enforcement typically lives: IdP vs. application
In an OKTA-centralized architecture (§6/§8), MFA enforcement almost always
lives **at the IdP**, not in each individual application — OKTA challenges
for a second factor as part of the Authorization Code flow's login step
(`diagrams/oauth2-oidc-pkce-flow.md`'s "submits credentials (+ MFA challenge
if enrolled)" step), and every application federated to that OKTA tenant
gets MFA enforcement for free, centrally configurable (which apps require
it, which factor types are allowed, step-up requirements for sensitive
actions) without any per-application MFA implementation at all. Building MFA
into an individual application (this module's `AuthController` explicitly
does *not* implement it, noting this in its Javadoc) only makes sense when
that application is genuinely its own IdP with no federation — the minority,
not the majority, architecture at a company already running enterprise SSO.

### When to use / when NOT to use
- Enforce MFA at the IdP for anything federated — it's centrally managed,
  consistently enforced across every connected app, and dramatically reduces
  account-takeover risk from credential-stuffing/phishing for a relatively
  small UX cost.
- Consider **step-up** authentication (re-prompt for a second factor even
  within an existing session) for specifically sensitive actions — e.g.
  changing a payout bank account, approving a large transaction — even if
  the initial login didn't require it, or required a weaker factor.
- Building your own MFA implementation into an application that already sits
  behind a federated IdP is usually redundant effort and a worse security
  posture than just enforcing it centrally at the IdP.

### Trade-offs
Every additional factor adds user friction — real usability/support cost
(password-reset-style support tickets for "I lost my authenticator app" are
a real, ongoing operational burden) traded against materially reduced
account-takeover risk. Push and TOTP generally offer a better
security-to-friction ratio than SMS.

### Enterprise examples
Financial-services companies (directly relevant to this curriculum's
JPMorgan/Goldman Sachs/S&P Global focus) near-universally mandate MFA for
any employee or customer-facing system handling money movement or sensitive
financial data, typically enforced centrally at the IdP with step-up
requirements for high-risk actions specifically — this is a default
assumption an interviewer at such a company will expect you to already hold
without being told.

### Common mistakes
- Implementing MFA per-application in an org that already has a federated
  IdP — inconsistent enforcement, duplicated effort, and a worse user
  experience (different MFA prompts/flows per app) than centralizing it.
- Treating all MFA factors as equally strong — SMS is meaningfully weaker
  than TOTP/push/hardware keys and shouldn't be presented as an
  equivalent choice for high-value account protection without caveating that.

---

## 10. Filters & JWT Validation in Practice

### What it is
`JwtAuthenticationFilter` (`extends OncePerRequestFilter`) is where all the
theory above becomes an actual request-processing step: extract the Bearer
token, hand it to `JwtService` for validation, and — only on success —
populate `SecurityContextHolder` so everything downstream (`@PreAuthorize`,
`Authentication`-aware code) sees an authenticated caller.

### Why `OncePerRequestFilter` specifically
A plain `javax.servlet.Filter`/`jakarta.servlet.Filter` can be invoked more
than once for a single logical client request under internal
forward/error/include dispatches (e.g. an error page rendered via a servlet
forward re-enters the filter chain). `OncePerRequestFilter` guarantees
exactly-once execution per request regardless of internal dispatch, which
matters here specifically because re-running JWT parsing/validation twice
per request would be wasted work at best and a source of subtle
double-processing bugs at worst.

### Where it sits, and why that position matters
Registered via `addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`
— before Spring Security's own username/password filter would run (disabled
in this module, but that's still the conventional slot for a custom
pre-authentication filter) and, transitively, before
`authorizeHttpRequests(...)`'s access-decision logic evaluates, so that by
the time authorization rules run, the `SecurityContext` already reflects
whatever the token proved (or didn't).

### When to use / when NOT to use a custom filter vs. Spring's built-in OAuth2 resource server support
Use a custom filter (this module's approach) when you are the token issuer
and need full, explicit control/visibility over every validation step for
teaching or highly-customized-claim purposes. Use Spring Security's built-in
`oauth2ResourceServer(...)` (§6) when validating tokens issued by an
external IdP — it already correctly implements JWKS fetching/caching/
rotation, which a hand-rolled filter would otherwise have to reimplement
(and would be easy to get subtly wrong, e.g. not handling key rotation via
`kid` at all).

### Trade-offs & performance implications
A hand-rolled filter has zero extra dependencies beyond the JWT library
itself and full transparency for teaching, but takes on the responsibility
of getting every validation detail right (§11) with no framework safety net;
`oauth2ResourceServer(...)` outsources that correctness to a well-audited,
widely-used implementation at the cost of slightly less visibility into
exactly what's happening (though it's open source and inspectable, of
course).

### Enterprise examples
Most real Spring Boot services validating externally-issued tokens use
`spring-boot-starter-oauth2-resource-server` directly rather than a
hand-rolled filter like this module's, specifically to avoid re-implementing
JWKS handling — this module's `JwtAuthenticationFilter` earns its keep
specifically in the "we are the issuer" scenario, which is also common
(internal service-to-service tokens, or a smaller product that hasn't
federated to an external IdP yet).

### Common mistakes
Covered in depth, deliberately as their own top-level section, below.

---

## 11. Common Token-Validation Mistakes

This section is written as a standalone checklist because token-validation
bugs are one of the most heavily-weighted security topics at interviews for
companies handling money movement or sensitive data (S&P Global, JPMorgan,
Goldman Sachs in particular, per this curriculum's target list) — expect a
senior-round interviewer to walk through several of these directly, asking
"what's wrong with this code" for a deliberately-broken JWT validator.

1. **Not checking expiry at all.** The single most basic mistake — accepting
   a token indefinitely regardless of its `exp` claim. `JwtService.validateAndParse`
   relies on jjwt's built-in automatic `exp` enforcement (throwing
   `ExpiredJwtException`) specifically so this can't be silently skipped by
   a future edit; a hand-rolled parser that reads claims into a plain map
   and never separately checks `exp` against "now" has this bug by
   omission, not by any obviously wrong-looking line.

2. **Not verifying the signature at all — or verifying it against the wrong
   key/algorithm family ("`alg: none`" and algorithm-confusion attacks).**
   The classic version: an early, naive JWT library implementation looked at
   the attacker-controlled header's `alg` field to decide *how* to verify,
   so an attacker could set `"alg": "none"`, strip the signature section
   entirely, and have a forged token accepted because the code path for
   `"none"` skipped verification altogether. A related, subtler version:
   a server configured to verify with an HMAC secret is tricked into
   accepting a token whose header claims `RS256`, where the "signature" is
   actually an HMAC computed using the RSA **public** key (which, unlike the
   private key, is not secret) as the HMAC secret — if the verifier naively
   uses whatever algorithm the header names, this forges a valid-looking
   signature. **The fix, and what `JwtService` does:** never let the
   token's own header dictate the verification algorithm/key. The verifier
   decides, out of band, ahead of time, which algorithm and which key apply
   — jjwt enforces this structurally, since `verifyWith(SecretKey)` and
   `verifyWith(PublicKey)` are different method overloads that only accept
   compatible header algorithms, not "whatever the header says."

3. **Not validating the issuer (`iss`).** Without this check, a token
   legitimately issued by a *different*, equally-real system your
   application happens to trust the signing key of for some other reason
   (a staging environment sharing a secret with production by mistake; a
   sibling product at the same company using the same IdP tenant but meant
   for a different audience) can be replayed against an endpoint it was
   never intended for. `JwtService` enforces this via `requireIssuer(issuer)`,
   and the OAuth2 Resource Server path enforces it automatically against
   `issuer-uri`.

4. **Not validating the audience (`aud`).** Related to #3 but distinct: even
   from the *correct* issuer, a token minted for a different downstream API
   (e.g. an `id_token`, whose audience is the client app, not any API — see
   §6) should be rejected by an API that isn't its intended audience.
   Spring Security's OAuth2 Resource Server support validates issuer and
   expiry automatically but does **not** validate audience out of the box —
   this is application-specific and easy to forget; see the comment at the
   bottom of `application-oauth2-resource-server-example.yml`.

5. **Storing JWTs insecurely client-side — `localStorage` vs. `httpOnly`
   cookie.**
   | | `localStorage` / `sessionStorage` | `httpOnly` cookie |
   |---|---|---|
   | **Readable by JavaScript** | Yes — any script running on the page (including an XSS payload) can read it | No — `httpOnly` explicitly blocks `document.cookie` access |
   | **XSS risk** | High — a single successful XSS injection can exfiltrate every stored token | Much lower — XSS still allows the attacker to *ride along* using the cookie's ambient auth via a forged request from the page context, but cannot directly *read and exfiltrate* the token value itself |
   | **CSRF risk** | None (not ambient — must be explicitly attached to a request by application JavaScript) | Present — must be paired with CSRF protection (§3) since the browser attaches it automatically |
   | **Common real-world choice** | Common for access tokens in SPA architectures, accepting the XSS trade-off in exchange for CSRF-freedom, *provided* the app has strong XSS defenses (CSP, output encoding, dependency hygiene) | Common specifically for refresh tokens (longer-lived, higher value if stolen) — swapping one risk (XSS exfiltration) for another (CSRF), which must then be mitigated |

   There is no universally "correct" answer here — it's a genuine trade-off
   an interviewer wants you to articulate, not a single memorized best
   practice. A defensible modern pattern many companies use: short-lived
   access token in memory/`sessionStorage` (accepting some XSS exposure,
   bounded by the token's short TTL) + refresh token in an `httpOnly`,
   `Secure`, `SameSite=Strict` cookie (protected from XSS exfiltration, its
   CSRF exposure mitigated by `SameSite` plus the fact that refreshing a
   token has no destructive side effect an attacker gains from forging
   alone, unlike, say, a funds transfer).

6. **Trusting claims without re-validating structural assumptions** — e.g.
   assuming a `roles` claim is always present and always a `List<String>`
   without defensive handling, so a malformed or adversarially-crafted (but
   somehow still validly-signed, e.g. from a compromised low-trust issuer)
   token causes a `ClassCastException` or `NullPointerException` deep in
   authorization logic instead of a clean rejection.
   `JwtAuthenticationFilter` guards this by validating each role name against
   the known `Role` enum and discarding unrecognized ones rather than
   failing the whole request or granting an unintended authority.

---

## Next module

Module 5 (`spring/`), being built concurrently, adds the REST controllers
this module's `SecurityConfig` and `@PreAuthorize` examples are written to
protect. Module 7 (`database/`) will eventually back `DemoUserDirectory`
with a real persistent user store; Module 9 (`angular/`) will be the actual
consumer of this module's login endpoint and the Authorization Code + PKCE
flow described in `diagrams/oauth2-oidc-pkce-flow.md`.
