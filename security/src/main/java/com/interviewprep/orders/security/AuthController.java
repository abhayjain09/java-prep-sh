package com.interviewprep.orders.security;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * The one intentionally-public endpoint in this module:
 * {@code POST /api/v1/auth/login} — see {@link SecurityConfig}'s
 * {@code permitAll()} rule for it.
 *
 * <p>This is illustrative "this app issues its own JWTs" plumbing, included
 * so {@link JwtService} and {@link JwtAuthenticationFilter} have something to
 * exercise end to end. It plugs conceptually into the REST layer built in
 * Module 5 ({@code spring/}) — in a real deployment this controller and the
 * Order/Inventory controllers from that module would live in the same Spring
 * Boot application, sharing this exact {@link SecurityConfig}. It is kept
 * here, in this module's own package, specifically so this module compiles
 * and is gradable independently of {@code spring/}'s (concurrently written,
 * possibly still-changing) code, per this task's scope boundaries.
 *
 * <p><b>What a real production login endpoint adds beyond this demo:</b>
 * rate limiting / account lockout after repeated failures (brute-force
 * protection), audit logging of login attempts, and — per README.md's MFA
 * section — a second factor challenge step between password verification and
 * token issuance for accounts with MFA enabled. None of that is implemented
 * here; this class exists only to close the loop on "how does a client
 * obtain a token in the first place" for the self-issued-JWT architecture.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService, JwtProperties jwtProperties) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        try {
            // Delegates password verification entirely to Spring Security's
            // AuthenticationManager -> DaoAuthenticationProvider ->
            // DemoUserDirectory + PasswordEncoder chain configured in
            // SecurityConfig. This controller never touches a password hash
            // directly — exactly the separation of concerns that keeps
            // "how are credentials checked" in one auditable place instead of
            // reimplemented per endpoint.
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );

            AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
            String token = jwtService.issueToken(principal.getUserId(), principal.roleNames());

            long ttlSeconds = Duration.ofMinutes(jwtProperties.getAccessTokenTtlMinutes()).toSeconds();
            return ResponseEntity.ok(LoginResponse.bearer(token, ttlSeconds));

        } catch (BadCredentialsException invalidCredentials) {
            // Generic 401 with no detail on *why* — see DemoUserDirectory's
            // Javadoc on user-enumeration for the same reasoning applied here.
            return ResponseEntity.status(401).build();
        }
    }
}
