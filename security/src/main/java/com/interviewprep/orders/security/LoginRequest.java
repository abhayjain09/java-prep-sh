package com.interviewprep.orders.security;

/**
 * Login request body for {@code POST /api/v1/auth/login}. A record, in
 * keeping with this repo's convention (Module 1) of using records for
 * immutable data carriers with no identity/lifecycle of their own — a login
 * request is exactly that.
 */
public record LoginRequest(String username, String password) {
}
