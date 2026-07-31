# Module 6 — Exercises

Do these in order — each builds on the previous one's code. Work in
`security/src/main/java/com/interviewprep/orders/security/`. Unlike Module 1,
this module *does* have a real Maven build (`security/pom.xml`) and a real
test dependency (`spring-security-test`) already wired in — so exercises 3–6
ask you to write actual `@SpringBootTest`/`MockMvc` tests, not just eyeball
console output. None of this has been run in this sandbox (no JDK/Maven
available here) — verify on your own machine.

## 1. (Beginner) Add an `ADMIN`-only operation

`SecuredInventoryOperations` currently has a `MANAGER`-only `restock` method
and a `MANAGER`-or-`CUSTOMER` `viewStock` method. Add a third method,
`deleteProduct(String sku)`, restricted to `ADMIN` only via `@PreAuthorize`.

**Check yourself:** should `ADMIN` also be able to call `restock` and
`viewStock`? As written, an `ADMIN`-only token would currently be *rejected*
by `restock`'s `hasRole('MANAGER')` check and `viewStock`'s
`hasAnyRole('MANAGER', 'CUSTOMER')` check — is that the behavior you want?
If not, fix the expressions (hint: this is exactly the "should roles form a
hierarchy" design question README.md's RBAC section raises about `Role`).

## 2. (Beginner) Trace a request through the filter chain by hand

Without running any code, write out — in a comment block at the top of
`JwtAuthenticationFilter.java` — the exact sequence of method calls and
decisions that happen for a `GET /api/v1/products/abc` request with **no**
`Authorization` header at all, given `SecurityConfig`'s
`.requestMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()` rule.
Does `JwtAuthenticationEntryPoint` ever get invoked for this request? Why or
why not?

## 3. (Intermediate) Write a `JwtService` unit test — no Spring context needed

`JwtService` was deliberately kept framework-free (see EXPLANATION.md's note
on why `JwtConfig` exists as a separate adapter). Write a plain JUnit test
(no `@SpringBootTest`) that:
- constructs a `JwtService.usingHmacSecret(...)` directly with a literal
  base64url-encoded 32-byte secret,
- issues a token for a subject with `roles = List.of("MANAGER")`,
- calls `validateAndParse(...)` on it and asserts the subject and roles
  claim round-trip correctly,
- asserts that mutating even one character of the token string (simulating
  tampering) makes `validateAndParse(...)` throw.

**Check yourself:** what should happen if you construct a *second*
`JwtService` with a *different* secret and try to validate the first
service's token against it? Add that as a test case too, and confirm your
expectation matches `JwtService`'s Javadoc on signature verification.

## 4. (Intermediate) `MockMvc` test proving `@PreAuthorize` actually works

Using `spring-security-test`'s `SecurityMockMvcRequestPostProcessors.jwt()`
or a simpler `@WithMockUser(roles = "CUSTOMER")`-style annotation, write a
`@WebMvcTest`/`@SpringBootTest` test asserting:
- a `CUSTOMER`-only authenticated caller gets **403** calling
  `SecuredInventoryOperations.restock(...)` (directly, or via a thin test
  controller wrapping it),
- a `MANAGER`-authenticated caller gets a normal (non-403) result calling the
  same method.

**Check yourself:** temporarily delete `@EnableMethodSecurity` from
`SecurityConfig` and re-run this test. It should now fail differently (the
`CUSTOMER` caller's request *succeeds* instead of being rejected) — this is
the concrete proof, referenced in README.md's RBAC common-mistakes section,
of why "the annotation is present" is not the same as "the annotation is
enforced," and why a test like this one should exist for every
`@PreAuthorize`-protected method in a real codebase.

## 5. (Senior) Implement refresh-token rotation

`JwtService`/`AuthController` currently issue only a single, short-lived
(15-minute) access token with no refresh mechanism — logging back in from
scratch is the only way to get a new one once it expires. Design and
implement:
- a second, longer-lived (e.g. 7-day) refresh token, issued alongside the
  access token at login,
- a new `POST /api/v1/auth/refresh` endpoint that accepts a valid,
  non-expired refresh token and issues a *new* access token (and, for full
  rotation, a *new* refresh token, invalidating the old one),
- a decision, with a written justification in a code comment, on where the
  refresh token is stored server-side (if at all) to make "invalidate the
  old refresh token on rotation" possible — note this reintroduces some
  server-side state, which is the direct trade-off README.md's JWT section
  describes as the usual real-world mitigation for "JWTs can't be revoked
  early."

**Check yourself:** what stops an attacker who steals a refresh token from
using it indefinitely? Does your rotation scheme detect reuse of an
*already-rotated-away* refresh token (a strong signal of theft — the
legitimate client would never present a stale refresh token after
successfully rotating), and if so, what should happen when that's detected?

## 6. (Scenario) Design SSO + SCIM for a new enterprise customer

Your team's product currently only supports the self-issued-JWT login this
module implements (`DemoUserDirectory`, `AuthController`). A new enterprise
customer's contract requires: (a) their employees log in via their existing
OKTA tenant with no separate password for your product, and (b) when their
IT department deactivates an employee in OKTA, that employee loses access to
your product automatically, within minutes, with no manual step on either
side.

Write a short design doc (as a Markdown file or a long code comment — your
choice) covering:
- Which parts of *this module's* code would be deleted, kept, or replaced to
  satisfy requirement (a) — be specific about `SecurityConfig`,
  `JwtAuthenticationFilter`, `JwtService`, `AuthController`, and
  `DemoUserDirectory` individually.
- Which protocol satisfies requirement (b), and what new code (roughly —
  no need to implement it) your application would need to expose to receive
  those provisioning events.
- Whether requirement (a) and requirement (b) are actually the *same*
  integration or two *separate* integrations with OKTA — justify your answer
  using the distinction README.md §8 draws between SSO and SCIM.
- One thing that would break for existing (non-enterprise, self-service)
  customers still using `DemoUserDirectory`-style login if you accidentally
  wired requirement (a) as a global `SecurityConfig` change instead of a
  per-customer/per-tenant one — and how you'd structure the config to avoid
  that (hint: think about what "public" vs. "authenticated" even means when
  two totally different authentication mechanisms need to coexist for
  different customers of the same deployed application).
