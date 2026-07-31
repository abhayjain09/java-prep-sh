package com.interviewprep.orders.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * The single source of truth for HTTP-level security on the Order/Inventory
 * API: which endpoints are public, which require authentication, session
 * policy, CSRF policy, and where the custom JWT filter plugs into Spring
 * Security's filter chain.
 *
 * <p>{@code @EnableMethodSecurity} turns on {@code @PreAuthorize}/
 * {@code @PostAuthorize} support for the RBAC examples in
 * {@link SecuredInventoryOperations} — without it, those annotations are
 * silently ignored (no error, no enforcement — a genuinely dangerous silent
 * failure mode worth remembering: always verify method security is actually
 * wired up with a test that asserts a 403, not just by reading the
 * annotation and assuming it works).
 */
@Configuration
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final JwtService jwtService;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final CorsConfigurationSource corsConfigurationSource;

    public SecurityConfig(
            JwtService jwtService,
            JwtAuthenticationEntryPoint authenticationEntryPoint,
            CorsConfigurationSource corsConfigurationSource
    ) {
        this.jwtService = jwtService;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    /**
     * BCrypt with the library default work factor (10 as of Spring Security
     * 6). Used both by {@link DemoUserDirectory}'s stored hashes and by
     * {@link DaoAuthenticationProvider} below to check a submitted password
     * against them. Never swap this for a fast general-purpose hash
     * (MD5/SHA-256/SHA-512) for password storage — those are optimized for
     * speed, which is exactly the wrong property when the threat model is
     * "attacker has a stolen hash database and is brute-forcing offline."
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Standard Spring Security authentication plumbing:
     * {@code DaoAuthenticationProvider} checks a username/password pair
     * against a {@link UserDetailsService} (here, {@link DemoUserDirectory})
     * using the {@link PasswordEncoder} above, and {@link ProviderManager}
     * (Spring Security's standard {@link AuthenticationManager}
     * implementation — a small chain of one-or-more
     * {@code AuthenticationProvider}s tried in order) exposes that as the
     * bean {@link AuthController} injects and calls {@code authenticate(...)}
     * on with the credentials from a login request. This is the idiomatic
     * way to verify a password in Spring Security — never compare raw or
     * hashed passwords by hand in a controller.
     */
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

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtService);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // --- CORS -----------------------------------------------------
                // Delegates to the allow-listed CorsConfigurationSource bean
                // (CorsConfig.java) rather than any wildcard/reflected-origin
                // logic. See CorsConfig's Javadoc for the vulnerability this
                // avoids.
                .cors(cors -> cors.configurationSource(corsConfigurationSource))

                // --- CSRF -------------------------------------------------------
                // Disabled here, deliberately, with the reasoning spelled out in
                // full because "just disable CSRF" without justification is
                // exactly the kind of unexamined copy-paste that gets flagged in
                // a senior-level security review:
                //
                // CSRF (Cross-Site Request Forgery) exploits the fact that
                // browsers automatically attach *ambient* credentials — cookies —
                // to requests, including ones triggered by a malicious
                // third-party page the victim happens to have open (an
                // auto-submitting form, an <img> tag hitting a GET endpoint with
                // side effects, etc.). The attacker's page can't read the
                // response (that's what CORS/same-origin-policy blocks), but a
                // CSRF attack doesn't need to read the response — it only needs
                // the side effect (e.g. "transfer $500", "change my email") to
                // happen, riding on the victim's already-authenticated session
                // cookie.
                //
                // This API is STATELESS and uses Bearer tokens carried in the
                // `Authorization` HTTP header, not cookies. A browser does NOT
                // automatically attach an arbitrary header to a cross-origin
                // request the way it attaches cookies — the attacker's page has
                // no mechanism to make the victim's browser send
                // "Authorization: Bearer <token>" on a forged request, because
                // the attacker's JavaScript has no access to that token in the
                // first place (it's not sitting in an ambient, browser-managed
                // store the way a cookie is). With no ambient credential for the
                // browser to auto-attach, the entire premise of a CSRF attack
                // does not apply to this endpoint shape. This is precisely why
                // Spring Security's own documentation recommends disabling CSRF
                // for stateless, token-based APIs that don't use cookies.
                //
                // *** This justification evaporates the moment cookies re-enter
                // the picture. *** If this API (or a sibling app sharing this
                // security config) ever issues a session cookie for ANY purpose
                // — including a "remember me" cookie, an httpOnly refresh-token
                // cookie (a legitimate, common pattern for storing refresh
                // tokens more safely than localStorage — see README.md), or
                // classic server-side sessions — CSRF protection must be
                // re-enabled for the endpoints that accept that cookie, because
                // now there IS an ambient credential a forged cross-site request
                // can ride on. The correct mental model is not "JWT APIs never
                // need CSRF protection," it's "CSRF protection is about
                // cookie-based ambient auth specifically; enable it wherever
                // that pattern exists, regardless of whether JWTs are ALSO in use
                // elsewhere in the same system."
                .csrf(AbstractHttpConfigurer::disable)

                // --- Session policy ---------------------------------------------
                // STATELESS: Spring Security will never create or read an
                // HttpSession for authentication purposes. Every request must
                // carry (and does carry, via JwtAuthenticationFilter) everything
                // needed to authenticate it. This is what makes horizontal
                // scaling trivial — any instance behind a load balancer can
                // handle any request, with no session-affinity/sticky-session
                // requirement and no shared session store (Redis, etc.) needed
                // just for auth.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // --- Unauthenticated-request handling ---------------------------
                .exceptionHandling(eh -> eh.authenticationEntryPoint(authenticationEntryPoint))

                // --- Authorization rules -----------------------------------------
                .authorizeHttpRequests(auth -> auth
                        // Login must be reachable by definition without a token yet.
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        // Health checks for load balancers / orchestrators (ECS,
                        // Kubernetes) must not require auth, or the platform can
                        // never determine liveness.
                        .requestMatchers("/actuator/health").permitAll()
                        // Example of a deliberately public, read-only slice of an
                        // otherwise-protected API — a product catalog is typically
                        // fine to browse anonymously even though placing an order
                        // is not. Contrast with SecuredInventoryOperations, where
                        // the equivalent distinction is enforced at the method
                        // level via @PreAuthorize instead of the URL level — both
                        // are legitimate places to draw this line; using both
                        // together (as here) is defense in depth, not redundancy.
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()
                        // Everything else requires a valid, authenticated caller.
                        // Role-specific restrictions beyond "authenticated" (e.g.
                        // "only MANAGER may restock") are enforced by
                        // @PreAuthorize at the method level, not here — see
                        // README.md's RBAC section for why method-level security
                        // is generally preferred over trying to encode every role
                        // rule as a URL pattern (URL patterns get unmanageable fast
                        // once authorization depends on more than just "is this
                        // endpoint public or not").
                        .anyRequest().authenticated()
                )

                // Insert our stateless JWT filter where Spring Security's own
                // username/password filter would otherwise run. We disable that
                // filter's usual triggers below, but this is still the
                // conventional insertion point for any custom
                // pre-authentication filter.
                .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)

                // Explicitly disable mechanisms Spring Security would otherwise
                // auto-configure for a traditional browser app (an HTML login
                // form, an HTTP Basic prompt) — neither makes sense for a
                // stateless JSON API authenticating via Bearer tokens, and
                // leaving them enabled is a common source of confusing behavior
                // (e.g. a browser popping up a Basic-auth dialog on a 401 that
                // was meant to be handled by frontend JavaScript instead).
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);

        // --- Alternative path: validating externally-issued tokens ----------
        // If this API validated tokens issued by an external IdP (OKTA, Auth0,
        // Azure AD) instead of self-issuing them, the JwtAuthenticationFilter
        // wiring above would be replaced entirely with:
        //
        //   http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwtConfigurer -> {}));
        //
        // with `spring.security.oauth2.resourceserver.jwt.issuer-uri` pointing
        // at the IdP (see application-oauth2-resource-server-example.yml and
        // README.md §6). Spring Security would then fetch the IdP's public
        // signing keys from its JWKS endpoint automatically, cache and
        // periodically refresh them, and validate signature/expiry/issuer
        // without any of JwtService/JwtAuthenticationFilter's code existing at
        // all. This module implements the hand-rolled version because it's
        // more instructive to see every validation step explicitly, and
        // because this app IS the token issuer in this module's scenario
        // (there's no reachable external IdP in this sandbox to integrate
        // with for real) — but a production system fronted by real SSO would
        // very likely use this simpler, framework-managed path instead.

        return http.build();
    }
}
