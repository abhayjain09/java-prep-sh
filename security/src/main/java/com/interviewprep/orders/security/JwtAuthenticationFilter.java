package com.interviewprep.orders.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A servlet filter that runs once per request (guaranteed by extending
 * {@link OncePerRequestFilter} — plain {@code Filter} implementations can be
 * invoked more than once per request under internal forwards/error dispatches,
 * which would otherwise risk re-parsing/re-validating the same token
 * redundantly), extracts a Bearer token from the {@code Authorization}
 * header, validates it via {@link JwtService}, and — only on success —
 * populates Spring Security's {@link SecurityContextHolder} so that
 * downstream {@code @PreAuthorize} checks and {@code SecurityContext}-based
 * lookups (e.g. "who is the current user") work for the rest of the request.
 *
 * <h2>Where this sits in the filter chain</h2>
 * <p>Registered in {@link SecurityConfig} via
 * {@code .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)}
 * — i.e. before Spring Security's own form-login filter would run (which this
 * module disables anyway, since it's a stateless JSON API, but the insertion
 * point is the conventional place any custom pre-authentication filter goes).
 *
 * <h2>What this filter deliberately does NOT do</h2>
 * <ul>
 *   <li>It does not reject the request itself on a missing/invalid token. It
 *       simply leaves the {@code SecurityContext} empty and calls
 *       {@code filterChain.doFilter(...)} to continue. Whether that's a
 *       problem is decided later, by {@code authorizeHttpRequests(...)} in
 *       {@link SecurityConfig} (public endpoints are fine with no
 *       authentication; protected ones will be rejected downstream) and
 *       rendered by {@link JwtAuthenticationEntryPoint}. This separation —
 *       "authenticate if possible" here, "decide if that's sufficient" in
 *       config, "render the rejection" in the entry point — is the same
 *       single-responsibility argument this repo makes elsewhere (e.g.
 *       Module 1's {@code OrderStatus} deciding legality vs. {@code Order}
 *       applying it).</li>
 *   <li>It does not hit a database. See {@link AppUserPrincipal}'s Javadoc —
 *       this is the point of stateless JWT auth.</li>
 * </ul>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String header = request.getHeader(AUTHORIZATION_HEADER);

        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            // No bearer token presented at all — could be an anonymous
            // request to a public endpoint (fine) or a protected one that
            // will be rejected downstream (also fine, that's not this
            // filter's job to decide). Just continue the chain.
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length());

        try {
            Claims claims = jwtService.validateAndParse(token);

            String userId = claims.getSubject();
            List<String> roleNames = jwtService.extractRoles(claims);
            Set<Role> roles = new HashSet<>();
            if (roleNames != null) {
                for (String name : roleNames) {
                    // EnumSet-style guard: a role name in the token that
                    // doesn't map to a known Role (e.g. the token was issued
                    // by a newer version of this service with a role this
                    // instance doesn't know about yet, or is simply
                    // corrupt/forged-but-somehow-signed data) is skipped
                    // rather than throwing and failing the whole request —
                    // deliberately fail-open on *unknown extra* roles but
                    // fail-closed overall (a request ends up with fewer
                    // privileges than the token claims, never more).
                    try {
                        roles.add(Role.valueOf(name));
                    } catch (IllegalArgumentException unknownRole) {
                        log.warn("JWT for subject {} contained unrecognized role '{}' — ignoring it", userId, name);
                    }
                }
            }

            AppUserPrincipal principal = AppUserPrincipal.fromJwtClaims(userId, userId, roles);

            var authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null, // credentials — irrelevant post-token-validation; the signature already proved identity
                    principal.getAuthorities()
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (JwtException | IllegalArgumentException invalidToken) {
            // Deliberately broad catch, deliberately not distinguishing
            // "expired" from "bad signature" from "malformed" in what we do
            // next (log server-side for debugging; do NOT leak the
            // distinction to the client — see JwtService.validateAndParse's
            // Javadoc for why). We do NOT set an authentication, and we do
            // NOT write a response here — that's JwtAuthenticationEntryPoint's
            // job, triggered later if this turns out to be a protected
            // endpoint.
            log.debug("Rejected invalid bearer token: {}", invalidToken.getMessage());
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Skips this filter entirely for the login endpoint. Not strictly
     * required for correctness (the filter would just find no header, or an
     * irrelevant one, and pass through harmlessly) but it documents intent
     * and shaves a small amount of work off the one endpoint guaranteed to
     * be called by unauthenticated clients on every login.
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api/v1/auth/login");
    }
}
