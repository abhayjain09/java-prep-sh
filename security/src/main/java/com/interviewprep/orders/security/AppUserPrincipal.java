package com.interviewprep.orders.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The custom {@link UserDetails} implementation ("Authentication model" per
 * this module's brief) that represents an authenticated caller of the
 * Order/Inventory API for the lifetime of a single request.
 *
 * <p><b>Two very different ways a value of this type gets constructed, and why
 * that distinction matters:</b>
 * <ol>
 *   <li><b>At login</b> ({@link DemoUserDirectory}, acting as a Spring
 *       {@code UserDetailsService}): built from a real (in this demo,
 *       in-memory) user record, including a password hash, so
 *       {@code AuthenticationManager} can verify the submitted password
 *       against it.</li>
 *   <li><b>On every subsequent authenticated request</b>
 *       ({@link JwtAuthenticationFilter}): built directly from the claims
 *       already inside a validated JWT — subject and roles — with
 *       <em>no database lookup at all</em>. This is the whole point of
 *       stateless JWT auth: the token itself is the source of truth for "who
 *       is this and what roles do they have," so request #2 through #N never
 *       touch a user store just to authenticate. (You may still hit a
 *       database for other reasons on those requests — e.g. loading the
 *       customer's own orders — but that is an authorization-time /
 *       business-logic concern, not part of authenticating the caller.)</li>
 * </ol>
 *
 * <p><b>Trade-off worth saying out loud in an interview:</b> because
 * subsequent requests trust the token's claims instead of re-checking a user
 * store, a role change (e.g. an admin revokes a user's MANAGER role) does not
 * take effect until that user's current access token expires — there is no
 * way to force-invalidate a single already-issued stateless JWT server-side
 * without maintaining some server-side state (a denylist / short TTL +
 * refresh token rotation — see README.md's JWT section). This is the classic
 * "stateless is fast but revocation is hard" trade-off interviewers probe
 * for directly.
 */
public final class AppUserPrincipal implements UserDetails {

    private final String userId;
    private final String username;
    /** Nullable/blank once reconstructed from a JWT — see the class Javadoc. */
    private final String passwordHash;
    private final Set<Role> roles;

    public AppUserPrincipal(String userId, String username, String passwordHash, Set<Role> roles) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.username = Objects.requireNonNull(username, "username");
        this.passwordHash = passwordHash; // intentionally nullable, see Javadoc
        this.roles = Set.copyOf(roles);   // defensive copy, same pattern as Module 1's Order.getLines()
    }

    /**
     * Factory used by {@link JwtAuthenticationFilter} to rebuild a principal
     * purely from validated token claims. No password hash exists at this
     * point (and none is needed — the token itself already proved identity
     * via its signature), so it's explicitly {@code null}.
     */
    public static AppUserPrincipal fromJwtClaims(String userId, String username, Set<Role> roles) {
        return new AppUserPrincipal(userId, username, null, roles);
    }

    public String getUserId() {
        return userId;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    /**
     * Spring Security's {@code hasRole('MANAGER')} SpEL expression checks for
     * a granted authority literally named {@code "ROLE_MANAGER"} — the
     * {@code ROLE_} prefix is a Spring Security convention, not a JWT/OAuth2
     * standard, and is exactly what {@code hasRole(...)} adds implicitly
     * before comparing (whereas {@code hasAuthority(...)} does not — a very
     * common source of "why does hasRole('MANAGER') never match" bugs when a
     * team's JWT claims already contain the literal string "ROLE_MANAGER" and
     * someone double-prefixes it, or vice versa forgets the prefix mapping
     * entirely).
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    // The four account-state flags below are all part of the UserDetails
    // contract and default to "healthy" here because this demo has no
    // account-lockout / expiry / credential-rotation policy implemented. A
    // real enterprise app wires these to real columns (lockedUntil,
    // passwordExpiresAt, disabledAt, ...) so Spring Security can reject
    // authentication for a disabled/locked account automatically, in one
    // place, instead of that check being reimplemented ad hoc at every
    // controller.
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /** Convenience for {@link JwtService} claim extraction call sites. */
    public List<String> roleNames() {
        return roles.stream().map(Role::name).collect(Collectors.toUnmodifiableList());
    }
}
