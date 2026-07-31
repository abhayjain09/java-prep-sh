# Module 6 — Line-by-Line Explanation

Walks through every file in
[src/main/java/com/interviewprep/orders/security](src/main/java/com/interviewprep/orders/security)
in dependency order (roughly: domain-ish types first, then services, then
the filter/config layer that wires everything together, then the endpoint
that exercises it end to end). Full "why" for each design choice is also in
the inline Javadoc — this file adds narrative connecting choices *across*
files, the way Module 1's `EXPLANATION.md` does.

## `Role.java`

```java
public enum Role { CUSTOMER, MANAGER, ADMIN }
```
Same argument as Module 1's `OrderStatus`: a closed, compiler-checked set
beats a free-text role string. Every `hasRole('MANAGER')`-style SpEL
expression elsewhere in this module implicitly depends on `"MANAGER"`
exactly matching one of these three constants — a typo here would be a
compile error; a typo in a hypothetical `String role` field would be a
silent runtime authorization bug.

## `AppUserPrincipal.java`

```java
public final class AppUserPrincipal implements UserDetails {
```
Implements Spring Security's `UserDetails` contract — the interface
`DaoAuthenticationProvider` (login path) and any code inspecting
`SecurityContextHolder.getContext().getAuthentication().getPrincipal()`
(every subsequent authenticated request) both expect.

```java
public static AppUserPrincipal fromJwtClaims(String userId, String username, Set<Role> roles) {
    return new AppUserPrincipal(userId, username, null, roles);
}
```
Two call sites build this class very differently — see the class Javadoc's
numbered list. This factory method is the one `JwtAuthenticationFilter`
uses, and its `null` password parameter is a visible, intentional reminder
that a principal reconstructed from a token never had (and never needs) a
password in hand — the token's signature already proved identity.

```java
@Override
public Collection<? extends GrantedAuthority> getAuthorities() {
    return roles.stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
            .collect(Collectors.toUnmodifiableList());
}
```
This one line is *the* bridge between this module's `Role` enum and every
`hasRole(...)`/`hasAnyRole(...)` SpEL expression elsewhere — those
expressions check the `ROLE_`-prefixed authority strings this method
produces, not the `Role` enum directly.

## `JwtService.java`

The largest, most heavily-commented file in the module — see its own
Javadoc for the full HS256-vs-RS256 discussion. Three things worth calling
out that the Javadoc states but are easy to skim past:

```java
public enum Algorithm { HS256, RS256 }
```
Modeling algorithm as an enum field (set once, at construction, via one of
the two static factories) rather than trusting anything about *how* to
verify to the incoming token's own header is the structural fix for the
"alg: none" vulnerability class discussed in README.md §11 — the token
never gets a vote in which code path validates it.

```java
String token = switch (algorithm) {
    case HS256 -> builder.signWith(signingKey, Jwts.SIG.HS256).compact();
    case RS256 -> builder.signWith(signingKey, Jwts.SIG.RS256).compact();
};
```
Note this is a **switch expression** (Java 14+, same feature Module 1's
`OrderStatus.legalNextStates()` uses) — if a third `Algorithm` constant were
ever added, this fails to compile until handled, rather than silently
falling through.

```java
public Claims validateAndParse(String token) {
    var parserBuilder = Jwts.parser()
            .requireIssuer(issuer)
            .clockSkewSeconds(ALLOWED_CLOCK_SKEW_SECONDS);
    var parser = switch (algorithm) {
        case HS256 -> parserBuilder.verifyWith((SecretKey) verificationKey).build();
        case RS256 -> parserBuilder.verifyWith((PublicKey) verificationKey).build();
    };
    return parser.parseSignedClaims(token).getPayload();
}
```
Four checks happen in these few lines, three of them easy to miss if you
didn't read the Javadoc: signature (via `verifyWith`, enforced as part of
`parseSignedClaims`, not a separate step), expiry (`exp`, automatic —
jjwt throws `ExpiredJwtException` without any explicit code here), and
issuer (`requireIssuer`, explicit). README.md §11 walks through what goes
wrong when any one of these is silently skipped.

## `JwtProperties.java` / `JwtConfig.java`

Split in two deliberately: `JwtProperties` is a plain `@ConfigurationProperties`
POJO with getters/setters (record-style immutability doesn't play well with
Spring Boot's relaxed-binding property injection, which is why this one
class in the module isn't a record, unlike `LoginRequest`/`LoginResponse`);
`JwtConfig` is the thin `@Configuration` adapter that turns those properties
into the actual `JwtService` bean. This keeps `JwtService` itself
framework-agnostic — constructible directly with literal arguments in a
plain unit test, no Spring context required — while still letting the
secret/issuer/TTL be externally configured in a running application.

## `JwtAuthenticationFilter.java`

```java
public class JwtAuthenticationFilter extends OncePerRequestFilter {
```
See README.md §10 for why `OncePerRequestFilter` specifically, over a plain
`Filter`.

```java
if (header == null || !header.startsWith(BEARER_PREFIX)) {
    filterChain.doFilter(request, response);
    return;
}
```
No header (or a non-Bearer scheme) is not itself an error — it just means
this request proceeds unauthenticated, and whether that's acceptable is
decided later by `SecurityConfig`'s `authorizeHttpRequests(...)` rules. This
early return is the filter explicitly staying out of the authorization
decision.

```java
} catch (JwtException | IllegalArgumentException invalidToken) {
    log.debug("Rejected invalid bearer token: {}", invalidToken.getMessage());
    SecurityContextHolder.clearContext();
}
```
Deliberately broad, deliberately silent to the client (logged server-side
only) — see `JwtService.validateAndParse`'s Javadoc and README.md §11's
"don't leak which check failed" reasoning. Note the method has *no* `return`
here — execution falls through to `filterChain.doFilter(...)` at the bottom
regardless of whether the token was valid, invalid, or absent. The filter
always lets the request continue; it only ever decides whether to populate
the `SecurityContext` along the way.

```java
protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
    String path = request.getServletPath();
    return path.equals("/api/v1/auth/login");
}
```
A minor optimization, not a correctness requirement — see the Javadoc.
Removing this override would not change behavior for `/api/v1/auth/login`
(the filter would run, find no useful header on a typical login request,
and pass through), it would just do marginally more work per login call.

## `JwtAuthenticationEntryPoint.java`

```java
public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    ...
    objectMapper.writeValue(response.getOutputStream(), body);
}
```
This is the *only* place in the module that writes an actual 401 response
body. `JwtAuthenticationFilter` never writes to `response` directly on
failure — it just leaves the `SecurityContext` empty, and Spring Security's
own exception-handling machinery invokes this entry point later, once it's
established the request needed authentication and doesn't have it. This
two-step handoff (filter decides "is there a valid identity," entry point
decides "what does rejection look like on the wire") is what makes it
possible to swap the entry point's response format (e.g. to match a
different API's error-envelope convention) without touching token-validation
logic at all, and vice versa.

## `CorsConfig.java`

```java
configuration.setAllowCredentials(false);
```
This single line is the difference between "safe" and "the wildcard/
credentials vulnerability class" discussed at length in the class Javadoc
and README.md §4 — it's easy to read past as boilerplate, but it's actually
one of the more consequential lines in the whole module given how common the
wildcard-plus-credentials mistake is in real incident write-ups.

```java
source.registerCorsConfiguration("/api/**", configuration);
```
Scoped to `/api/**` specifically, not `/**` — this CORS policy has no
opinion about, say, an Actuator endpoint under a different path prefix, kept
narrow deliberately rather than defaulting to "apply everywhere."

## `DemoUserDirectory.java`

```java
private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
```
A `static final` instance, not a Spring-managed bean, specifically because
this class is scaffolding for the demo (see its own Javadoc on why it's "the
exception, not the pattern") — `SecurityConfig.passwordEncoder()` defines the
*real*, Spring-managed `PasswordEncoder` bean that `DaoAuthenticationProvider`
actually uses to check submitted passwords; this local `ENCODER` exists only
to pre-hash the two demo users' passwords once, at class-load time, without
needing a Spring context available yet at that point.

```java
if (user == null) {
    throw new UsernameNotFoundException("No such user: " + username);
}
```
Spring Security's `DaoAuthenticationProvider` catches this exception and
normalizes it, alongside a wrong-password outcome, into the same generic
`BadCredentialsException` — this class doesn't need to (and shouldn't) try
to distinguish the two itself; that normalization is the framework's job and
happens one layer up.

## `SecurityConfig.java`

The centerpiece — see README.md §2–§4 for the theory behind every clause.
Two sequencing details worth calling out that are easy to miss reading top
to bottom:

```java
@Bean
public AuthenticationManager authenticationManager(
        UserDetailsService userDetailsService,
        PasswordEncoder passwordEncoder
) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
    provider.setUserDetailsService(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder);
    return new ProviderManager(provider);
}
```
This bean depends on `PasswordEncoder` (defined just above it in the same
class) and an autowired `UserDetailsService` (satisfied by
`DemoUserDirectory`, the only bean of that type in the context) — Spring
resolves this dependency graph automatically; the ordering of methods within
the class is irrelevant to Spring, but is ordered here (encoder, then
manager, then filter, then the filter chain itself) to read top-to-bottom in
roughly the order a request actually flows through them. `ProviderManager`
(Spring Security's standard `AuthenticationManager` implementation) is
constructed directly here rather than obtained via Spring Boot's
`AuthenticationConfiguration` machinery — a deliberate choice to keep this
bean's wiring explicit and easy to trace by reading this one method, rather
than depending on Spring Boot's auto-configuration correctly discovering the
`UserDetailsService`/`PasswordEncoder` beans on its own.

```java
.addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
```
Calling `jwtAuthenticationFilter()` here (the `@Bean` method, not a field)
returns the same singleton Spring-managed instance as the `@Bean` itself —
this is safe specifically because the method is on a `@Configuration`-annotated
class, where Spring CGLIB-proxies the class so that repeated in-class calls
to another `@Bean` method resolve to the container's singleton instead of
constructing a fresh object each time. (Contrast with a plain `@Component`,
where calling another bean's factory method directly would *not* get this
treatment — a common, subtle Spring gotcha worth knowing for a senior
interview.)

## `SecuredInventoryOperations.java`

```java
@PreAuthorize("hasRole('MANAGER')")
public void restock(String sku, int quantity) { ... }

@PreAuthorize("hasAnyRole('MANAGER', 'CUSTOMER')")
public int viewStock(String sku) { ... }
```
The RBAC example the module brief calls for, side by side so the contrast is
immediate: one mutating, manager-only operation and one read-only operation
open to any authenticated role. Neither method contains a single line of
manual role-checking code — the entire enforcement is the annotation, backed
by `@EnableMethodSecurity` on `SecurityConfig` and the `ROLE_`-prefixed
authorities `AppUserPrincipal.getAuthorities()` produces.

## `LoginRequest.java` / `LoginResponse.java`

Both records — same reasoning as Module 1's `Customer`/`Product`: immutable
data carriers with no identity or lifecycle beyond their fields.
`LoginResponse.bearer(...)` is a named static factory rather than exposing
the canonical three-arg constructor as the primary way to build one, purely
for readability at the call site (`LoginResponse.bearer(token, ttl)` reads
better than `new LoginResponse(token, "Bearer", ttl)`, and centralizes the
literal `"Bearer"` string in one place).

## `AuthController.java`

```java
Authentication authentication = authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(request.username(), request.password())
);
```
This one line is doing much more than it looks like: it triggers
`DaoAuthenticationProvider` (registered in `SecurityConfig.authenticationManager(...)`)
to call `DemoUserDirectory.loadUserByUsername(...)`, then check the
submitted password against the stored BCrypt hash via the `PasswordEncoder`
bean — none of which this controller method needs to know about directly.
This is the payoff of wiring authentication through Spring's standard
`AuthenticationManager` abstraction instead of the controller doing
`if (password.equals(storedPassword))` by hand (which would also be a
critical bug on its own — comparing plaintext instead of a hash at all).

```java
AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
String token = jwtService.issueToken(principal.getUserId(), principal.roleNames());
```
Only after `authenticate(...)` succeeds (throwing `BadCredentialsException`
otherwise, caught below) does a token ever get minted — the JWT is a
*consequence* of successful authentication, never a substitute for it.

## `SecurityDemoApplication.java`

A minimal `@SpringBootApplication` entry point, needed only because this
module ships as an independently-runnable Maven module (see its own Javadoc
and `pom.xml`'s header comment for why, and why it wouldn't exist in a
merged deployment with Module 5's REST layer).
