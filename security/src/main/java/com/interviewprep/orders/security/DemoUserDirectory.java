package com.interviewprep.orders.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * A hardcoded, in-memory {@link UserDetailsService} that exists purely so
 * this module's {@link AuthController} demo has something to authenticate
 * against without a real database.
 *
 * <p><b>This class is the exception, not the pattern, in a real system.</b>
 * There are two realistic architectures for "where do users and passwords
 * live," and this class deliberately represents only one of them, kept
 * intentionally minimal:
 * <ol>
 *   <li><b>This app is its own identity provider</b> — a real
 *       {@code UserDetailsService} backed by a database table (Spring Data
 *       JPA, Module 5/7), passwords stored as salted hashes
 *       ({@link BCryptPasswordEncoder}, never plaintext, never a fast
 *       general-purpose hash like plain SHA-256). This class is a toy stand-in
 *       for that table.</li>
 *   <li><b>An external IdP (OKTA, Auth0, Azure AD, ...) owns identity</b> —
 *       this application never sees a password at all; it only validates
 *       tokens the IdP already issued (README.md §6, OAuth2 Resource Server).
 *       This is the more common enterprise pattern for a Senior Full Stack
 *       role's environment, and the one interviewers usually expect you to
 *       reach for when asked "how would you add login to this API" for an
 *       org that already has SSO — building option 1 from scratch would
 *       often be judged as a design smell in that context (reinventing
 *       authentication instead of federating to the org's existing IdP).</li>
 * </ol>
 *
 * <p>The two demo users below map onto {@link Role} to make
 * {@link SecuredInventoryOperations}'s RBAC examples exercisable end to end:
 * {@code alice} is a MANAGER (can restock), {@code bob} is a CUSTOMER
 * (read-only).
 */
@Component
public class DemoUserDirectory implements UserDetailsService {

    // BCrypt is intentionally slow (a configurable work factor / cost, 10 by
    // default) — the entire point is to make brute-forcing a stolen hash
    // database expensive, unlike a fast hash (MD5/SHA-256) designed for
    // speed, which is exactly the wrong property for password storage.
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    // Passwords here are "password123" for both demo users, hashed once at
    // class-load time. In a real system this table (and the encoder) live
    // behind a proper UserDetailsService backed by persistent storage; this
    // exists only so AuthController has something to check against.
    private final Map<String, AppUserPrincipal> usersByUsername = Map.of(
            "alice", new AppUserPrincipal(
                    "u-001", "alice", ENCODER.encode("password123"), Set.of(Role.MANAGER)),
            "bob", new AppUserPrincipal(
                    "u-002", "bob", ENCODER.encode("password123"), Set.of(Role.CUSTOMER))
    );

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUserPrincipal user = usersByUsername.get(username);
        if (user == null) {
            // Same "don't leak which check failed" principle as
            // JwtService.validateAndParse: Spring Security's
            // DaoAuthenticationProvider deliberately normalizes a
            // "no such user" outcome and a "wrong password" outcome into the
            // same generic BadCredentialsException at the authentication
            // layer, specifically so a login form/API can't be used to
            // enumerate valid usernames by observing different error
            // messages for "user not found" vs. "bad password."
            throw new UsernameNotFoundException("No such user: " + username);
        }
        return user;
    }
}
