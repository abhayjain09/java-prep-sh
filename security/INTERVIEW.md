# Module 6 — Interview Questions

Organized by topic, then by level (beginner → intermediate → senior →
scenario). Each includes an ideal answer outline and likely follow-ups.

**Security is disproportionately weighted at finance-sector companies.** At
S&P Global, JPMorgan, and Goldman Sachs specifically, expect a dedicated
security round (not just a couple of questions folded into a general
backend round) even for roles that aren't security-titled — these
organizations move money and sensitive financial data, are subject to heavy
regulatory scrutiny (SOX, PCI-DSS where applicable, various banking
regulations), and treat "can this candidate reason correctly about auth,
token handling, and access control" as a bar-raising signal independent of
general coding ability. Broader tech companies (Amazon, Microsoft, Google,
Oracle, Adobe, Salesforce, Atlassian) still ask this material, typically
folded into system-design or backend rounds rather than as a standalone
gate — but the depth expected of a "Senior" title is similar everywhere: not
just "what is a JWT" but "what's wrong with this JWT validation code" and
"how would you design offboarding across 50 SaaS apps."

---

## JWT

**Beginner:** "What is a JWT, and what are its three parts?"
*Ideal answer:* A compact, base64url-encoded, dot-separated string:
`header.payload.signature`. The header names the algorithm/type; the payload
is a JSON object of claims; the signature proves the first two parts weren't
altered since a trusted party signed them. Critically: the payload is
*encoded*, not encrypted — anyone can decode and read it.
*Follow-up:* "If anyone can read the payload, what shouldn't you put in it?"
→ Passwords, secrets, or sensitive PII beyond what's strictly needed for
authorization decisions (a user ID and roles are fine; a full SSN is not).

**Intermediate:** "Why would you choose RS256 over HS256 for a JWT, and
what does each require operationally?"
*Ideal answer:* HS256 (HMAC, symmetric) uses one shared secret for both
signing and verifying — simple, but every verifier must hold a secret
capable of also *forging* tokens, which is a real liability once more than
one service needs to verify tokens. RS256 (RSA, asymmetric) signs with a
private key and verifies with a distinct public key — the public key can be
freely distributed to every verifying service (or fetched from a JWKS
endpoint, as OKTA does) without any of them gaining the ability to issue
tokens. Preferred in multi-service architectures for exactly this reason.
*Follow-up:* "What's the performance cost of choosing RS256?" → RSA
signature verification is more computationally expensive per call than an
HMAC check, but this is almost always negligible next to typical request
latency (network, DB) — the choice is driven by trust/key-distribution, not
raw CPU cost.

**Senior:** "A stateless JWT can't be revoked before it expires. Walk
through how you'd actually mitigate that in a production system, and the
trade-offs of your approach."
*Ideal answer:* Keep access tokens short-lived (minutes), pair with a
longer-lived refresh token validated against a dedicated auth endpoint that
*can* check server-side state (e.g. "has this refresh token been revoked").
This bounds the un-revocable window to the access token's short TTL instead
of the user's whole session. A stronger variant: detect refresh-token reuse
after rotation (a strong signal of theft) and revoke the entire token
family, not just the reused token. Discuss the trade-off explicitly: this
reintroduces some server-side state (a revocation list or refresh-token
store) — the "purely stateless" promise of JWTs is never actually absolute
in a system that needs real-world revocation, it's a spectrum you tune via
TTL length.
*Follow-up:* "Why not just make access tokens very short (e.g. 30 seconds)
and skip refresh tokens entirely?" → Would require re-authenticating (full
login) every 30 seconds, which is a poor UX and (for password-based login)
increases exposure of credentials on the wire far more often than
necessary — refresh tokens exist precisely to decouple "how often can we
force a fresh authorization decision" (short) from "how often does the user
have to re-enter a password" (much longer).

**Scenario:** "You're reviewing a teammate's `JwtService`. It has a method
`decode(String token)` that base64-decodes the payload and reads claims,
with signature verification happening in a *separate* method that's called
conditionally in only some code paths. What's wrong, and what would you
recommend?"
*Ideal answer:* This is a critical vulnerability shape — any code path that
calls `decode(...)` without also calling the verification method is
trusting attacker-controlled, unverified data as if it were authenticated.
The fix (what `JwtService.validateAndParse` in this module does): make
verification structurally inseparable from parsing — one method that always
verifies as part of returning claims, with no "decode without verifying"
method exposed at all. This eliminates the entire class of "forgot to call
verify on this one code path" bugs by construction rather than by
discipline/code review alone.

---

## OAuth2 / OIDC

**Beginner:** "What's the difference between OAuth2 and OIDC?"
*Ideal answer:* OAuth2 is an authorization framework — it answers "can this
client do X on the user's behalf." It has no standardized concept of "who is
the user" on its own. OIDC is an identity layer built on top of OAuth2,
adding the `id_token` and standardized identity claims — it answers "who
just authenticated." "Log in with Google/OKTA" is OIDC, not bare OAuth2.
*Follow-up:* "Which one issues the access token you'd send to an API?" →
Both frameworks involve an access token (it's fundamentally an OAuth2
concept); OIDC additionally issues an ID token, which is for the client app,
not for calling APIs.

**Intermediate:** "Why does the Authorization Code flow use PKCE for a
single-page app specifically, and not for a traditional server-rendered web
app?"
*Ideal answer:* A traditional server-side web app is a "confidential
client" — it can hold a `client_secret` that never reaches the browser,
which the original Authorization Code flow used to prove the token-exchange
request came from the legitimate app. A browser SPA's entire codebase is
downloadable by anyone, so it cannot hold a secret confidentially — it's a
"public client." PKCE replaces the shared secret with a per-flow, one-time
challenge/verifier pair generated fresh by the SPA, so even if an
authorization code is intercepted mid-redirect, an attacker without the
matching `code_verifier` can't redeem it.
*Follow-up:* "Where does the `code_verifier` live, and does it ever appear
in a URL?" → Kept in the SPA's memory/session storage for the duration of
the flow; sent only once, in the body of the final `POST /token` call — it
never appears in a redirect URL (only its hashed form, the
`code_challenge`, does, in the initial `/authorize` redirect).

**Senior:** "Your Angular app's login flow ends up sending the `id_token`
(not the `access_token`) to your backend API in the `Authorization` header,
and it happens to work today. Why is this wrong, and what would you expect
to eventually break?"
*Ideal answer:* The `id_token`'s audience (`aud` claim) is the client
application itself, not the API — OIDC never specifies that a resource
server should trust or accept an ID token as an API credential. It "working
today" almost certainly means the resource server isn't validating `aud` at
all (README.md §11's audience-validation mistake) — a real vulnerability
independent of this specific bug, since it means the resource server would
also accept tokens minted for entirely unrelated client applications sharing
the same IdP tenant. The fix is two-layered: send the `access_token`, not
the `id_token`, from the SPA; and separately, fix the resource server to
actually validate `aud`, since the first fix alone doesn't address the
underlying gap that let the bug go unnoticed.
*Follow-up:* "How would you have caught this in code review without already
knowing the bug existed?" → Any PR touching token handling in a
frontend-to-API auth flow should prompt "which specific token is this, and
what is its `aud`" as a standing review question — this module's README
table (OIDC section) exists specifically as the mental checklist for that
review.

**Scenario:** "Design token validation for a new microservice that needs to
accept requests from users authenticated via your company's OKTA tenant.
Walk through what you'd configure and why you would (or wouldn't) write a
custom JWT filter."
*Ideal answer:* Use Spring Security's OAuth2 Resource Server support
(`spring-boot-starter-oauth2-resource-server`) configured with
`spring.security.oauth2.resourceserver.jwt.issuer-uri` pointing at the OKTA
tenant, rather than writing a custom filter — this gets JWKS fetching,
caching, and key-rotation handling for free from a well-audited
implementation, which a hand-rolled filter would have to reimplement
correctly (and easily wouldn't — e.g. failing to handle a `kid` key
rotation gracefully). Explicitly add an `aud` validator, since Spring
Security doesn't check audience by default. Reserve a custom filter (as this
module's `JwtAuthenticationFilter` demonstrates) for the case where *this*
service is itself the token issuer, not a verifier of an external IdP's
tokens.

---

## SAML

**Beginner:** "What problem does SAML solve, and how is it different from
OAuth2?"
*Ideal answer:* SAML is an older, XML-based standard for exchanging
authentication assertions between an IdP and a Service Provider —
conceptually similar to what OIDC does, but XML-based rather than
JSON/JWT-based, and designed primarily around full-page browser redirects
for enterprise SSO, predating modern SPA/mobile-API needs.
*Follow-up:* "Would you choose SAML for a brand-new mobile app's login?" →
No — SAML's redirect-heavy, XML-heavy design fits poorly with mobile/native
app UX; OIDC (Authorization Code + PKCE) is the modern default.

**Intermediate:** "Explain SP-initiated vs. IdP-initiated SAML SSO."
*Ideal answer:* SP-initiated: the user visits the application first; the
app, seeing no session, redirects to the IdP with an `AuthnRequest`, and the
IdP redirects back with a signed assertion. IdP-initiated: the user starts
at the IdP's own portal (e.g. clicking an app tile in OKTA) and is pushed
directly to the app with an assertion already in hand, no initial app-side
redirect.
*Follow-up:* "Which is generally considered slightly riskier, and why?" →
IdP-initiated, historically, because there's no app-generated request/
`RelayState` to correlate the response against, which has been a vector for
certain replay-style concerns in some implementations — many modern
deployments mitigate this with additional checks, but it's a commonly
raised nuance.

**Senior:** "A large enterprise customer says they can only integrate via
SAML, not OIDC. How do you scope that work relative to your existing OIDC
support, and what's the actual engineering delta?"
*Ideal answer:* SAML and OIDC solve the same conceptual problem
differently enough that supporting both usually means genuinely separate
code paths, not a thin adapter over one shared implementation — different
message formats (XML assertions with XML-DSig vs. JWTs), different flow
mechanics, and typically a dedicated SAML SP library rather than hand-rolled
XML signature validation (which has real historical vulnerability classes —
signature-wrapping attacks in particular). Scope it as a distinct,
non-trivial integration effort, not a checkbox next to existing OIDC
support.

**Scenario:** "How would you explain to a non-technical stakeholder why 'we
already support OKTA login' doesn't automatically mean 'we support this
customer's SAML-based IdP'?"
*Ideal answer:* "OKTA login" in your product likely means "OIDC federation
to OKTA acting as an OIDC provider" — but many IdPs (including OKTA itself,
which supports both protocols) can be configured to speak either OIDC or
SAML, and a customer's specific IT setup might only expose the SAML side to
you. Supporting "an OKTA-shaped OIDC integration" and "a SAML integration
with whatever IdP a customer runs, OKTA or otherwise" are different
technical commitments, even though "OKTA" is mentioned in both.

---

## OKTA & SCIM

**Beginner:** "What is OKTA, conceptually, in a system your application
integrates with?"
*Ideal answer:* A cloud Identity Provider (IdP) — the system employees (or
customers) actually authenticate against, and the central place roles/group
memberships and SSO are managed across every connected application.

**Intermediate:** "What is SCIM, and how is it different from SSO?"
*Ideal answer:* SSO (via OIDC or SAML) answers "how does an already-existing
user log in without a separate password." SCIM answers a different
question entirely: "how does that user's account get created, updated, or
removed from my application in the first place, automatically, as the
source-of-truth directory changes." They're complementary, not
alternatives — a mature enterprise integration typically implements both.
*Follow-up:* "Could you build SSO without SCIM?" → Yes — a user could
authenticate via SSO on first login and have an account provisioned "just
in time" at that moment (a real, valid pattern, "JIT provisioning") but
*deprovisioning* (removing access when someone leaves) has no equivalent
automatic trigger without SCIM — JIT provisioning alone doesn't solve
offboarding.

**Senior:** "Walk through, end to end, how you'd handle offboarding a user
across 50 connected SaaS applications automatically, and where SCIM fits."
*Ideal answer:* Each of the 50 applications implements (or is registered
with) a SCIM endpoint that OKTA's SCIM integration knows how to call. When
IT deactivates the employee in OKTA (a single action), OKTA's SCIM
provisioning engine pushes a deactivation (or delete, depending on
configuration) request to every connected application's SCIM endpoint,
typically within minutes. Each application's SCIM handler marks that user
inactive/removes their access locally. The key property: zero manual steps
per application after the initial SCIM integration is configured — this is
precisely the "how do you handle offboarding across 50 SaaS apps" scenario
interviewers probe for, and the expected answer names SCIM specifically, not
a vague "automate it somehow."
*Follow-up:* "What's a gap even a good SCIM integration might not close?" →
Sessions/tokens already issued *before* deactivation might remain valid
until they naturally expire (same revocation trade-off as JWTs generally,
README.md §1/§11) unless the application also actively checks a
"still active" flag on each request rather than relying solely on token
expiry — worth naming as a residual risk, not a solved problem.

**Scenario:** "A customer complains that after they deactivate an employee
in OKTA, that employee can still use your product for another 20 minutes.
Is this a bug, and how do you respond?"
*Ideal answer:* Likely not a bug in the SCIM integration itself (SCIM
propagation is typically near-real-time) but a consequence of the
architecture: if the employee already holds a valid access token, and
authorization is decided purely from that token's claims (stateless JWT,
this module's default pattern) rather than re-checked against current
account status on every request, the token remains functionally valid until
it expires — 20 minutes matches a plausible access-token TTL. The fix, if
faster revocation is a hard requirement, is either shortening the access
token TTL further, or checking a live "is this account still active" flag
on sensitive operations even when the token itself hasn't expired
(sacrificing some of the "no DB lookup needed" benefit of stateless JWTs for
that specific check) — a real, explicit trade-off to present to the
customer/stakeholders, not something to silently work around.

---

## MFA

**Beginner:** "What's the difference between something you know, have, and
are, in the context of MFA?"
*Ideal answer:* "Know" = a password/PIN. "Have" = a physical or virtual
possession — a phone running a TOTP app, a hardware security key. "Are" = a
biometric (fingerprint, face). MFA requires factors from at least two
different categories — two passwords, for instance, isn't MFA even though
it's two credentials.

**Intermediate:** "Why is TOTP generally considered stronger than
SMS-based MFA?"
*Ideal answer:* TOTP computes a code locally from a pre-shared secret plus
the current time — no network transmission of the code at all, so nothing
to intercept in transit. SMS-based codes travel over the cellular network
and are vulnerable to SIM-swapping (an attacker social-engineers a carrier
into porting the victim's number to a new SIM) and certain network-level
interception techniques. NIST guidance has deprioritized SMS as an
acceptable authenticator for these reasons, though it remains extremely
common for its low-friction reach.

**Senior:** "Your company federates every internal application to OKTA with
MFA enforced. A junior engineer proposes also adding a TOTP-based MFA step
inside one specific application's own login flow, 'for extra security.' How
do you respond?"
*Ideal answer:* Push back, and explain why: MFA enforcement centralized at
the IdP already covers every federated application uniformly, is centrally
configurable and auditable, and gives users one consistent MFA experience.
Bolting an *additional*, application-specific MFA step onto one app
specifically fragments that consistency, likely confuses users ("why does
this one app ask me twice"), and creates a second place MFA policy has to be
maintained and kept in sync — for no real security gain, since the
federated login already required MFA before the application ever saw the
user. The legitimate exception is *step-up* authentication for a
specifically sensitive in-app action (e.g. re-confirming MFA before
approving a large fund transfer) — that's an intentional, narrow escalation,
not a blanket duplicate of login-time MFA.

**Scenario:** "Users are complaining about 'MFA fatigue' — push
notifications repeatedly, sometimes at odd hours, and some have started
approving them just to make them stop. What's happening, and what would you
recommend?"
*Ideal answer:* This describes an active MFA-fatigue (push-bombing) social
engineering attack: an attacker with a stolen password triggers repeated
push prompts hoping the victim eventually approves one out of annoyance,
even without ever guessing a second factor honestly. Recommend
number-matching (the login screen shows a number the user must select/enter
in the authenticator app, rather than a bare "Approve?" button) and rate
limiting/backoff on repeated push attempts, plus alerting on unusual
push-approval patterns — this is a real, well-documented attack pattern
behind several high-profile breaches, and "just tell users to be more
careful" is not considered a sufficient answer at a senior level.

---

## RBAC & Method Security

**Beginner:** "What's the difference between authentication and
authorization?"
*Ideal answer:* Authentication answers "who are you" (proving identity —
this module's `JwtAuthenticationFilter`/login flow). Authorization answers
"what are you allowed to do" (this module's `@PreAuthorize` checks). A
request can be fully authenticated and still be denied by authorization
(a valid, correctly-signed token belonging to a `CUSTOMER` calling a
`MANAGER`-only endpoint) — the two are independent questions with
independent failure modes (401 vs. 403).

**Intermediate:** "In Spring Security, what's the difference between
`hasRole('MANAGER')` and `hasAuthority('MANAGER')`, and why does mixing
them cause bugs?"
*Ideal answer:* `hasRole('MANAGER')` implicitly checks for the authority
`"ROLE_MANAGER"` — Spring Security silently adds the `ROLE_` prefix.
`hasAuthority('MANAGER')` checks for the literal string `"MANAGER"`, no
prefix added. If a codebase's `UserDetails` implementation always emits
`ROLE_`-prefixed authorities (as this module's `AppUserPrincipal` does),
any `hasAuthority('MANAGER')` check (missing the prefix) silently never
matches — a real, previously-seen bug class, not a hypothetical one.
*Follow-up:* "How would you catch this in review without memorizing the
exact prefixing rule?" → A test (as this module's Exercise 4 asks for) that
actually asserts a 403/200 outcome for a given role catches this
immediately, regardless of whether the reviewer remembers the prefixing
convention — a strong argument for always testing `@PreAuthorize` rules
rather than trusting a read-through of the annotation.

**Senior:** "RBAC alone can't express 'a customer may view their own orders
but not anyone else's.' How would you extend this module's RBAC model to
support that, and what are the trade-offs?"
*Ideal answer:* That's an ownership/resource-instance check, not a role
check — RBAC answers "does this role have permission to call this kind of
operation at all," not "does this specific caller own this specific
resource." Extend with a `@PreAuthorize` SpEL expression comparing the
caller's identity to the resource's owner (e.g.
`@PreAuthorize("hasRole('CUSTOMER') and #customerId == authentication.principal.userId")`),
or push the check into the service layer where the resource is actually
loaded (often cleaner once the expression gets complex, since SpEL
expressions embedded in annotations become hard to read/test past a certain
complexity). Trade-off: SpEL-in-annotation is declarative and visible at a
glance but harder to unit test in isolation and can get unreadable fast;
service-layer checks are more testable but move the "who can do what" logic
out of the single declarative place `@PreAuthorize` otherwise centralizes it.

**Scenario:** "Code review: a teammate added `@PreAuthorize("hasRole('MANAGER')")`
to a new method, but the method is also called internally from another
method in the same `@Service` class via `this.thatMethod(...)`. What do you
flag?"
*Ideal answer:* Flag that Spring AOP method security only intercepts calls
that arrive through the Spring-managed proxy — a same-class,
`this.`-qualified internal call bypasses the proxy entirely, so the
`@PreAuthorize` check silently never runs for that call path. This is a
well-known Spring AOP limitation, not specific to Spring Security. Fixes:
restructure so the call goes through the proxy (e.g. inject the bean into
itself, or split the method into a separate bean), or, if the internal call
path is intentionally meant to bypass the check (e.g. a trusted internal
batch process), make that explicit and documented rather than accidental.

---

## CORS

**Beginner:** "Is CORS a server-side security control?"
*Ideal answer:* Not really — CORS is a browser-enforced relaxation of the
same-origin policy. The server sends `Access-Control-Allow-*` headers, but
it's the requesting *browser* that decides whether to expose the response to
calling JavaScript. A non-browser client (curl, another backend service)
ignores CORS entirely. CORS protects users from malicious cross-origin
*browser* scripts; it does not replace authentication/authorization.

**Intermediate:** "Why does a CORS preflight (`OPTIONS`) request happen for
some requests and not others?"
*Ideal answer:* "Simple requests" (certain methods, certain content types,
no custom headers) skip preflight. Anything outside that — notably, sending
a custom header like `Authorization`, which this module's API requires for
every authenticated call — triggers a preflight `OPTIONS` request first, so
the browser can confirm the server allows that exact method/header
combination from that origin before sending the real request with
potentially sensitive data.

**Senior:** "A security scan flags your CORS configuration for reflecting
the request's `Origin` header instead of using a fixed allow-list, combined
with `allowCredentials(true)`. Explain the actual exploit path to a
skeptical engineer who says 'but it's not a literal wildcard, so it's fine.'"
*Ideal answer:* The literal string `"*"` combined with credentials is
rejected by browsers per spec, true — but reflecting the request's `Origin`
value verbatim as the allowed origin is functionally identical in effect: it
means *every* origin is effectively allowed, it just isn't spelled `"*"`. An
attacker's page at `evil.com`, embedding a request to the vulnerable API,
gets back `Access-Control-Allow-Origin: evil.com` (reflected) plus
`Access-Control-Allow-Credentials: true`, which the browser *does* accept as
valid — so the victim's browser, if it holds a relevant cookie, will
actually send it and expose the response to `evil.com`'s script. This is a
real, exploitable vulnerability pattern with real incident history, not a
theoretical scanner false positive.

**Scenario:** "Your Angular app at `app.company.com` gets CORS errors
calling `api.company.com`, but only for POST requests with a JSON body —
GET requests work fine. What's the likely cause, and how do you fix it
without loosening the CORS policy unsafely?"
*Ideal answer:* POST with a JSON content type and a custom `Authorization`
header is a "non-simple" request, triggering a preflight `OPTIONS` check
that GET-without-custom-headers doesn't need. Likely cause: the CORS
configuration's `allowedMethods` or `allowedHeaders` doesn't include
`POST`/`Authorization` even though the actual endpoint's authorization rules
do. Fix: explicitly add the missing method/header to the existing allow-list
config (as this module's `CorsConfig` does) — not by loosening the origin
allow-list, which is a different, unrelated axis of the same configuration
and shouldn't be touched to fix a methods/headers problem.

---

## CSRF

**Beginner:** "What does CSRF stand for, and what does it exploit?"
*Ideal answer:* Cross-Site Request Forgery — it exploits a browser
automatically attaching ambient credentials (classically, a session cookie)
to a request, even one triggered by a malicious third-party page the victim
happens to have open, without the victim's knowledge.

**Intermediate:** "Why does this module disable CSRF protection, and is
that safe?"
*Ideal answer:* This API is stateless and authenticates via a Bearer token
in the `Authorization` header, never a cookie. CSRF specifically exploits
ambient, browser-auto-attached credentials — a custom header isn't ambient;
a browser has no mechanism to make a victim's browser attach an arbitrary
header to a forged cross-site request, and the attacker's page has no way to
read the token to attach it manually either. With no ambient credential, the
CSRF attack has nothing to ride on for this endpoint shape, so disabling
CSRF protection here is safe *and specifically justified*, not a default
copy-pasted from a tutorial.
*Follow-up:* "What would make that justification stop being true?" → The
moment this application (or any endpoint sharing the same
`SecurityFilterChain`) issues or accepts a cookie for any auth-adjacent
purpose — a session cookie, a "remember me" cookie, an httpOnly refresh-token
cookie — CSRF protection needs to come back for those specific endpoints.

**Senior:** "Your team is moving refresh tokens from `localStorage` into an
httpOnly cookie for better XSS resistance. What security control do you now
need to add that you didn't need before, and why?"
*Ideal answer:* CSRF protection for the `/refresh` endpoint (and any other
endpoint that will now accept that cookie) — moving to a cookie
reintroduces an ambient credential a forged cross-site request could ride
on, which wasn't a concern when the refresh token lived in `localStorage`
(not ambient, requires application JavaScript to explicitly read and attach
it). This is a direct trade: better XSS resistance (httpOnly blocks
JavaScript from reading the cookie) in exchange for reintroducing CSRF
exposure, mitigated via a CSRF token, `SameSite=Strict`/`Lax`, or a
double-submit-cookie pattern.

**Scenario:** "A junior engineer says 'we use JWTs everywhere, so we don't
need CSRF protection anywhere in the whole system.' Is this correct?"
*Ideal answer:* No — the correct scope of the claim is per-endpoint, not
per-system: "endpoints that authenticate via a Bearer token in a header,
with no cookie involved, don't need CSRF protection." If even one endpoint
in the same system accepts a cookie for any purpose (a legacy session, a
refresh-token cookie, anything), *that* endpoint needs CSRF protection
regardless of how many other endpoints in the same system use header-based
JWTs. Treating "we use JWTs" as a blanket, system-wide exemption is exactly
the kind of overgeneralization that leads to a missed CSRF vulnerability on
the one endpoint that's actually cookie-based.

---

## Token-Validation Mistakes (cross-cutting)

**Beginner:** "Name two things a JWT validator must check beyond 'is this
valid base64/JSON.'"
*Ideal answer:* Signature (proves it wasn't forged/tampered) and expiry
(proves it's still within its validity window). (A strong answer also
mentions issuer and/or audience.)

**Intermediate:** "What is the 'alg: none' JWT vulnerability?"
*Ideal answer:* Some early/naive JWT libraries let the token's own header
dictate which verification algorithm to use. An attacker could set the
header's `alg` to `"none"`, strip the signature section entirely, and have
the library's `"none"` code path accept it as valid with no actual signature
check performed at all — because verification behavior was driven by
attacker-controlled input instead of the verifier's own, fixed
configuration.
*Follow-up:* "How does a well-designed JWT library structurally prevent
this?" → By making the caller specify, ahead of time, out of band from the
token itself, which algorithm/key to verify with — e.g. jjwt's
`verifyWith(SecretKey)` vs. `verifyWith(PublicKey)` are different method
overloads that only accept compatible algorithm families, so the token's
header can influence *whether* verification succeeds, never *how* it's
performed.

**Senior:** "Review this (deliberately flawed) pseudocode and list every
bug:
```
claims = base64Decode(token.split('.')[1])
if (claims.userId != null) { grantAccess(claims); }
```"
*Ideal answer:* No signature verification at all (any attacker can craft
arbitrary claims), no expiry check, no issuer/audience check, and — more
subtly — even the presence check (`claims.userId != null`) is meaningless
security-wise since the entire claims object is attacker-controllable
without a preceding verification step. This is the "never write your own
JWT parsing without a library that structurally enforces verification"
lesson in concrete, worst-case form.

**Scenario:** "Your team's incident review after a security audit flags:
'the API accepts tokens from our staging IdP tenant in production.' Walk
through how that's possible and how you'd fix it."
*Ideal answer:* Almost certainly a missing or overly-permissive issuer
check — if production's resource server never validates `iss` against its
own expected production issuer value, a token from a *different*, equally
validly-signed source (staging's IdP tenant, if it happens to share
infrastructure or a misconfigured trust relationship with production) would
pass signature verification and be accepted. Fix: explicitly configure and
enforce the exact expected `issuer-uri`/`iss` value in production's resource
server config (`JwtService.validateAndParse`'s `requireIssuer(...)` in this
module's hand-rolled path, or `issuer-uri` in the OAuth2 Resource Server
path) and add this exact scenario (cross-environment token replay) as a
standing test case, not just a one-time config fix.
