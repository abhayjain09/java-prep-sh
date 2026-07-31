package com.interviewprep.orders.security;

/**
 * Login response body. Deliberately returns the access token in the JSON
 * body (for the client to store and send back as
 * {@code Authorization: Bearer <token>}) rather than setting it as a cookie —
 * see README.md's token-storage discussion (localStorage vs. httpOnly cookie
 * trade-offs) for why *this* module's Angular-facing contract chooses the
 * header/body approach, and what it would mean to choose the cookie approach
 * instead (it would reintroduce the need for CSRF protection — see
 * {@code SecurityConfig}'s CSRF Javadoc).
 */
public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds) {

    public static LoginResponse bearer(String accessToken, long expiresInSeconds) {
        return new LoginResponse(accessToken, "Bearer", expiresInSeconds);
    }
}
