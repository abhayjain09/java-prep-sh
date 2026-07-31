package com.interviewprep.orders.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Issues and validates JSON Web Tokens for the Order/Inventory API.
 *
 * <h2>Algorithm choice: HS256 vs RS256 — and why this class supports both</h2>
 * <p>A JWT's header names the signing algorithm (e.g. {@code "alg": "HS256"}).
 * This module implements <b>both</b> families because the choice is one of
 * the most common senior-level security interview questions, and the honest
 * answer is "it depends which side of a service boundary you're on":
 * <ul>
 *   <li><b>HS256 (HMAC-SHA256, symmetric)</b> — one shared secret both signs
 *       and verifies. Simple, fast, and perfectly fine when exactly one
 *       service issues tokens and that same service (or a small, tightly
 *       trusted set of services you fully control) is the only verifier —
 *       e.g. a monolith issuing its own session tokens. The catch: <i>every</i>
 *       service that needs to verify a token must possess the same secret
 *       capable of also <i>forging</i> tokens. Leak that secret to one
 *       over-permissioned microservice and it can mint tokens claiming to be
 *       any user with any role.</li>
 *   <li><b>RS256 (RSA-SHA256, asymmetric)</b> — a private key signs, a
 *       mathematically related but distinct public key verifies. This is
 *       generally preferred in multi-service / microservice architectures
 *       (the scenario a "Senior Full Stack" interview almost always frames
 *       this question around) because the public key can be distributed
 *       freely — baked into config, fetched from a JWKS endpoint, whatever —
 *       to every downstream service that only needs to <i>verify</i> tokens,
 *       without any of them ever being able to <i>issue</i> one. Only the
 *       auth server holds the private key. This is exactly the shape of an
 *       external IdP like OKTA: it publishes a public JWKS endpoint
 *       (`/.well-known/jwks.json`) so any number of resource servers can
 *       validate its tokens, and Spring Security's OAuth2 Resource Server
 *       support (README §6) fetches and caches that automatically.</li>
 * </ul>
 * <p>Performance note: RSA signature verification is measurably more
 * expensive per-call than HMAC (public-key crypto vs. a keyed hash), but in
 * practice this is noise next to typical request latency (network, DB) — the
 * choice is almost always made for the trust/key-distribution reason above,
 * not raw CPU cost.
 *
 * <h2>Why this class, and not a home-grown signer</h2>
 * <p>Signing/verifying is delegated entirely to {@code io.jsonwebtoken} (jjwt)
 * rather than hand-rolling base64url encoding + HMAC/RSA calls. "Never write
 * your own crypto" applies just as much to token handling as to encryption —
 * jjwt's parser, in particular, refuses to verify a token whose header
 * algorithm family doesn't match the key type you hand it (you cannot
 * accidentally call {@code verifyWith(SecretKey)} and have it silently accept
 * an RSA-signed or unsigned token) — this is precisely the class of bug
 * behind the classic <b>"alg: none" JWT vulnerability</b> discussed in
 * README.md's token-validation-mistakes section: some early, naive JWT
 * libraries would look at the attacker-supplied header's {@code alg} field
 * and use *that* to decide how to verify, so an attacker could set
 * {@code "alg": "none"}, strip the signature, and have a forged token
 * accepted as valid. This class never lets the token's header dictate which
 * verification path runs — the caller (this service, configured at startup)
 * decides the algorithm and key, once, out of band from anything a request
 * can influence.
 */
public class JwtService {

    /** The two algorithm families this teaching module supports. */
    public enum Algorithm {
        HS256,
        RS256
    }

    private final Algorithm algorithm;
    private final Key signingKey;
    private final Key verificationKey;
    private final String issuer;
    private final Duration accessTokenTtl;
    /** Small allowed clock drift between the host that issued a token and the
     *  host validating it — real fleets are never perfectly NTP-synced to the
     *  millisecond. Too generous a value weakens expiry enforcement; too
     *  strict causes spurious rejections right at the boundary. 30s is a
     *  common, conservative default. */
    private static final long ALLOWED_CLOCK_SKEW_SECONDS = 30;

    /**
     * HS256 constructor — one shared secret for both signing and verifying.
     *
     * @param base64UrlEncodedSecret the shared secret, base64url-encoded. It
     *      must decode to at least 256 bits (32 bytes) for HS256 — jjwt
     *      throws {@code WeakKeyException} at startup otherwise, which is a
     *      deliberate fail-fast: a too-short HMAC key is brute-forceable and
     *      should never reach production. In real deployments this value
     *      comes from an environment variable / secrets manager (see
     *      {@code security.jwt.secret} in application.yml and the AWS
     *      Secrets Manager discussion in README.md) — never hardcoded or
     *      committed to source control.
     */
    public static JwtService usingHmacSecret(String base64UrlEncodedSecret, String issuer, Duration accessTokenTtl) {
        SecretKey key = Keys.hmacShaKeyFor(java.util.Base64.getUrlDecoder().decode(base64UrlEncodedSecret));
        return new JwtService(Algorithm.HS256, key, key, issuer, accessTokenTtl);
    }

    /**
     * RS256 constructor — asymmetric key pair. {@code privateKey} signs
     * (kept secret, held only by the auth server); {@code publicKey} verifies
     * (freely distributable to every resource server). In production these
     * are typically loaded from a keystore (PKCS#12/JKS) or, for an external
     * IdP scenario, the public half is fetched dynamically from the IdP's
     * JWKS endpoint rather than configured statically at all — see the
     * OAuth2 Resource Server discussion in README.md §6, which replaces this
     * entire class with framework-managed JWKS-based validation when the
     * token issuer is a third party like OKTA rather than this application
     * itself.
     */
    public static JwtService usingRsaKeyPair(PrivateKey privateKey, PublicKey publicKey, String issuer, Duration accessTokenTtl) {
        return new JwtService(Algorithm.RS256, privateKey, publicKey, issuer, accessTokenTtl);
    }

    private JwtService(Algorithm algorithm, Key signingKey, Key verificationKey, String issuer, Duration accessTokenTtl) {
        this.algorithm = algorithm;
        this.signingKey = Objects.requireNonNull(signingKey, "signingKey");
        this.verificationKey = Objects.requireNonNull(verificationKey, "verificationKey");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.accessTokenTtl = Objects.requireNonNull(accessTokenTtl, "accessTokenTtl");
    }

    /**
     * Issues (signs) a new access token.
     *
     * <p>Claims included, and why each one matters:
     * <ul>
     *   <li>{@code sub} (subject) — who this token represents. Kept to a
     *       stable user id, not a mutable field like email, so a later email
     *       change doesn't orphan already-issued tokens.</li>
     *   <li>{@code roles} (custom claim) — this is what makes RBAC work
     *       statelessly; see {@link AppUserPrincipal} and
     *       {@link JwtAuthenticationFilter}.</li>
     *   <li>{@code iss} (issuer) — checked on validation so a token issued by
     *       a *different* trusted-in-theory system (e.g. a staging
     *       environment's auth server, or another product's auth server if
     *       your org runs several) can't be replayed against this one. This
     *       is the "not validating issuer" mistake from README.md — omitting
     *       this check is a real, exploitable gap, not a theoretical one.</li>
     *   <li>{@code iat} / {@code exp} (issued-at / expiry) — bound the
     *       token's lifetime. Short-lived access tokens (minutes, not days)
     *       limit the blast radius of a leaked token — see the refresh-token
     *       discussion in README.md.</li>
     * </ul>
     *
     * <p>Deliberately <b>not</b> included: password, email, or any other PII
     * beyond what authorization logic actually needs. A JWT's payload is only
     * base64url-<i>encoded</i>, not encrypted — anyone holding the token
     * (including client-side JavaScript, browser devtools, or a proxy log
     * that captures headers) can trivially decode and read every claim. Never
     * put secrets or sensitive PII in a JWT payload; if you truly need
     * confidentiality of claims, that's what JWE (encrypted JWTs) is for —
     * out of scope here because it's rarely used in practice compared to
     * plain signed JWTs (JWS), which is what "JWT" means colloquially and
     * throughout this module.
     */
    public String issueToken(String subject, List<String> roles) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(subject)
                .claim("roles", roles)
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)));

        // Explicit algorithm pinning (not "let the key decide implicitly")
        // is a deliberate defense: signWith(key, explicitAlgorithm) fails
        // fast at issuance time if the key and algorithm are mismatched
        // (e.g. an RSA key handed to an HMAC signer), rather than silently
        // doing something unexpected.
        String token = switch (algorithm) {
            case HS256 -> builder.signWith(signingKey, Jwts.SIG.HS256).compact();
            case RS256 -> builder.signWith(signingKey, Jwts.SIG.RS256).compact();
        };
        return token;
    }

    /**
     * Validates a token's signature, expiry, and issuer, and returns its
     * claims if — and only if — all checks pass.
     *
     * <p>What "validate" means here, spelled out because skipping any one of
     * these is a real vulnerability class covered in README.md:
     * <ol>
     *   <li><b>Signature</b> — proves the token was issued by a holder of the
     *       signing key (this service, for HS256; the auth server's private
     *       key, for RS256) and hasn't been tampered with since. This is the
     *       check that a naive "just base64-decode and read the JSON" (never
     *       do this) skips entirely.</li>
     *   <li><b>Expiry (`exp`)</b> — jjwt throws {@code ExpiredJwtException}
     *       automatically if the token's expiry has passed (accounting for
     *       {@link #ALLOWED_CLOCK_SKEW_SECONDS}). Skipping this check would
     *       let a leaked token from months ago remain forever valid.</li>
     *   <li><b>Issuer (`iss`)</b> — {@code requireIssuer} rejects tokens
     *       missing this claim or bearing a different one.</li>
     * </ol>
     *
     * @return the validated claims (subject, roles, etc.)
     * @throws JwtException if the token is malformed, unsigned, signed with
     *      the wrong algorithm/key, expired, or issued by an unexpected
     *      issuer. Callers (here, {@link JwtAuthenticationFilter}) catch this
     *      broadly and treat any failure identically — "not authenticated" —
     *      deliberately not leaking *which* check failed back to the client,
     *      since that distinction (expired vs. forged vs. malformed) is
     *      exactly the kind of detail that helps an attacker iterate faster.
     */
    public Claims validateAndParse(String token) {
        var parserBuilder = Jwts.parser()
                .requireIssuer(issuer)
                .clockSkewSeconds(ALLOWED_CLOCK_SKEW_SECONDS);

        var parser = switch (algorithm) {
            // verifyWith(SecretKey) vs verifyWith(PublicKey) are different
            // overloads — jjwt uses the overload itself to constrain which
            // header "alg" values it will even consider (HMAC family vs
            // RSA/EC family). This is the library-level fix for the
            // "alg: none" / algorithm-confusion class of vulnerability
            // described in the class Javadoc above.
            case HS256 -> parserBuilder.verifyWith((SecretKey) verificationKey).build();
            case RS256 -> parserBuilder.verifyWith((PublicKey) verificationKey).build();
        };

        // parseSignedClaims (jjwt 0.12.x API) verifies the signature as part
        // of parsing — there is no separate "parse first, verify later" step
        // to accidentally forget, unlike some older JWT libraries' APIs.
        return parser.parseSignedClaims(token).getPayload();
    }

    /** Convenience used by {@link JwtAuthenticationFilter}. */
    @SuppressWarnings("unchecked")
    public List<String> extractRoles(Claims claims) {
        return claims.get("roles", List.class);
    }
}
