package com.interviewprep.orders.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * CORS (Cross-Origin Resource Sharing) configuration for the Order/Inventory
 * API.
 *
 * <h2>What CORS actually protects against — and what it doesn't</h2>
 * <p>CORS is a <b>browser-enforced</b> relaxation mechanism for the
 * same-origin policy, not a server-side security control in itself. The
 * server sends {@code Access-Control-Allow-*} headers; it's the
 * <i>requesting browser</i> that decides whether to expose the response to
 * the calling JavaScript based on those headers. A non-browser client (curl,
 * another backend service, Postman, a malicious script running server-side)
 * ignores CORS headers entirely and can call this API directly regardless of
 * what's configured here. CORS exists to protect *your users* — stopping
 * {@code evil.com}'s JavaScript, running in a victim's browser, from reading
 * responses from `api.your-company.com` using the victim's ambient
 * credentials (cookies) — it is not a substitute for authentication or
 * authorization, both of which this module still fully enforces
 * independently via {@link JwtAuthenticationFilter} and {@code @PreAuthorize}.
 *
 * <h2>The dangerous combination this config deliberately avoids</h2>
 * <p><b>Wildcard origin ({@code "*"}) + {@code allowCredentials(true)}</b> is
 * a real, well-known vulnerability pattern:
 * <ul>
 *   <li>The CORS spec itself, and every modern browser, actually
 *       <i>rejects</i> the literal combination of {@code Access-Control-Allow-Origin: *}
 *       together with {@code Access-Control-Allow-Credentials: true} — so
 *       this exact literal misconfiguration typically just breaks
 *       (credentialed requests silently fail), which is a safe failure mode.</li>
 *   <li>The <i>real-world, exploitable</i> version of this mistake is
 *       functionally equivalent but passes browser validation: dynamically
 *       <b>reflecting whatever {@code Origin} header the request sent</b>
 *       back as the literal {@code Access-Control-Allow-Origin} value (e.g.
 *       {@code response.setHeader("Access-Control-Allow-Origin", request.getHeader("Origin"))}
 *       with no allow-list check), combined with {@code allowCredentials(true)}.
 *       This achieves the same effect as a true wildcard-with-credentials —
 *       *any* origin's script can make credentialed requests and read the
 *       response — but because the header value isn't the literal string
 *       {@code "*"}, browsers accept it. This exact pattern has shipped in
 *       real production incidents at companies that "fixed" a CORS error by
 *       reflecting the origin instead of checking it against an allow-list.</li>
 * </ul>
 * <p>This module avoids both forms by (a) using an explicit, finite allow-list
 * of origins rather than any reflection logic, and (b) not using cookies for
 * authentication at all — the Bearer token travels in the {@code Authorization}
 * header, which is not "ambient" the way cookies are (a browser does not
 * automatically attach it to cross-origin requests the way it does a cookie),
 * so {@code allowCredentials(false)} is correct here. See README.md's CSRF
 * section for the related discussion of why cookie-based session auth and
 * bearer-token auth have fundamentally different cross-origin risk profiles.
 *
 * <h2>If this API also issued a session cookie (it does not, but for
 * completeness)</h2>
 * <p>You would need {@code allowCredentials(true)} (so the browser attaches
 * the cookie cross-origin) <b>and</b> a strict origin allow-list (never a
 * wildcard, never reflection) <b>and</b> CSRF protection re-enabled (see
 * {@link SecurityConfig}'s CSRF discussion) — cookies plus a loose CORS
 * policy is one of the most common real-world paths to account takeover in
 * web apps that mix both auth styles.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Explicit allow-list — the Angular dev server (Module 9) and a
        // placeholder production origin. In a real deployment these come
        // from environment-specific configuration (application-{profile}.yml
        // or an environment variable), never hardcoded per-environment
        // values baked into one shared config class.
        configuration.setAllowedOrigins(List.of(
                "http://localhost:4200",                 // Angular CLI dev server
                "https://orders.interviewprep.example.com" // placeholder prod origin
        ));

        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        // Authorization must be explicitly allowed — it is NOT one of the
        // CORS "simple request" headers, so without this, the browser's
        // preflight (OPTIONS) check fails before the actual GET/POST request
        // carrying the Bearer token is ever sent, a very common "CORS is
        // blocking my API calls" debugging session for anyone new to bearer
        // tokens from a browser SPA.
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        // See class Javadoc: no cookies are used for auth, so there is no
        // need for the browser to send credentials cross-origin, and keeping
        // this false is itself a defense-in-depth measure (it also means a
        // wildcard origin would at least not compound into the
        // wildcard+credentials vulnerability class, even though this config
        // doesn't use a wildcard anyway).
        configuration.setAllowCredentials(false);

        // How long the browser may cache a preflight (OPTIONS) response
        // before re-checking — reduces preflight round-trips for repeat
        // calls without meaningfully weakening anything (the actual
        // request/response is still governed by the live server-side
        // authorization checks on every call).
        configuration.setMaxAge(java.time.Duration.ofHours(1).toSeconds());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
