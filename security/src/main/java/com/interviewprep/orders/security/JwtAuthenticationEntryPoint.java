package com.interviewprep.orders.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders the actual HTTP 401 response whenever an unauthenticated request
 * reaches a protected endpoint.
 *
 * <p>This is a deliberately separate class from {@link JwtAuthenticationFilter}
 * because they answer two different questions:
 * <ul>
 *   <li>{@code JwtAuthenticationFilter} asks "given what's on this request,
 *       who (if anyone) is making it?"</li>
 *   <li>{@code JwtAuthenticationEntryPoint} asks "given that nobody is
 *       authenticated, and this endpoint required someone to be, what does
 *       the client see?"</li>
 * </ul>
 * <p>Spring Security invokes an {@link AuthenticationEntryPoint} automatically
 * whenever an {@link AuthenticationException} propagates out of the filter
 * chain / access-decision layer for an unauthenticated request — wired in
 * via {@code .exceptionHandling(eh -> eh.authenticationEntryPoint(...))} in
 * {@link SecurityConfig}. Without registering one explicitly, Spring
 * Security's default behavior for a servlet app is to redirect to a login
 * page (assuming form-login/browser usage) — completely wrong for a JSON
 * API, which should return a machine-readable 401 body instead.
 *
 * <p>Note the response body intentionally contains no detail about *why*
 * authentication failed (missing header vs. expired token vs. bad signature)
 * — see {@link JwtService#validateAndParse}'s Javadoc for the same
 * information-leakage reasoning.
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpServletResponse.SC_UNAUTHORIZED);
        body.put("error", "Unauthorized");
        body.put("message", "A valid Bearer token is required to access this resource.");
        body.put("path", request.getRequestURI());

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
