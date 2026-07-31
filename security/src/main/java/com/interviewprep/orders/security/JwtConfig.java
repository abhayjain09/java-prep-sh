package com.interviewprep.orders.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires {@link JwtProperties} (bound from {@code application.yml}) into a
 * {@link JwtService} bean. Kept as its own tiny {@code @Configuration} class
 * rather than annotating {@code JwtService} itself with {@code @Component} +
 * {@code @Value} fields, so {@code JwtService} stays a plain, framework-free,
 * easily-unit-testable class (construct it directly with literal values in a
 * test, no Spring context needed) — the Spring wiring is an adapter around
 * it, not baked into it. This mirrors the "keep domain/business logic free of
 * framework annotations where practical" principle this repo has followed
 * since Module 1's plain-Java domain classes.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    @Bean
    public JwtService jwtService(JwtProperties properties) {
        // This module wires HS256 by default (see application.yml) because
        // it's the simpler end-to-end demo (one app both issues and
        // validates its own tokens). Swapping to
        // JwtService.usingRsaKeyPair(privateKey, publicKey, issuer, ttl)
        // here is the only change needed to move to RS256 — nothing in
        // JwtAuthenticationFilter or SecurityConfig needs to know which
        // algorithm is in use, since JwtService hides that behind a single
        // issue/validate API. See JwtService's class Javadoc for the full
        // HS256 vs RS256 trade-off discussion.
        return JwtService.usingHmacSecret(
                properties.getSecret(),
                properties.getIssuer(),
                Duration.ofMinutes(properties.getAccessTokenTtlMinutes())
        );
    }
}
