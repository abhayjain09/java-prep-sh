package com.interviewprep.orders.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding for the {@code security.jwt.*} keys in
 * {@code application.yml}. Using {@code @ConfigurationProperties} instead of
 * scattering {@code @Value("${security.jwt.secret}")} across classes
 * centralizes validation (see {@link #secret}'s Javadoc) and makes every
 * configurable knob discoverable in one place.
 *
 * <p><b>Where {@link #secret} actually comes from in each environment</b> —
 * this is a common follow-up question interviewers ask after any JWT
 * discussion ("okay, where does the secret live?"):
 * <ul>
 *   <li><b>Local dev:</b> a value in {@code application.yml} or an
 *       {@code .env} file that is <i>never</i> committed to source control
 *       (see this repo's {@code .gitignore}).</li>
 *   <li><b>CI/staging/production:</b> injected as an environment variable or
 *       resolved at startup from a secrets manager (AWS Secrets Manager /
 *       Parameter Store — covered in the AWS module — or HashiCorp Vault),
 *       never baked into a container image or checked into any repo. This is
 *       exactly the class of secret an interviewer expects you to name
 *       AWS Secrets Manager, not "an environment variable someone pasted into
 *       Jenkins," for at a company running real infrastructure.</li>
 *   <li><b>Multi-instance deployments:</b> every instance of this service
 *       needs the identical secret (HS256) so tokens issued by instance A
 *       validate on instance B — another reason RS256 is often preferred at
 *       scale: only the (typically single, or small HA pair of) auth-issuing
 *       component needs the private key; every API instance only needs the
 *       public key, which is safe to bake into config or fetch from a JWKS
 *       endpoint without the same blast-radius-if-leaked concern.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "security.jwt")
public class JwtProperties {

    /**
     * Base64url-encoded HMAC secret. Must decode to >= 256 bits for HS256 —
     * see {@link JwtService#usingHmacSecret}. Left as a plain String binding
     * (Spring Boot's config binder handles environment variable / secrets
     * manager resolution transparently via property placeholder syntax like
     * {@code ${JWT_SECRET}} in the YAML — the Java code here never needs to
     * know which backing source supplied the value).
     */
    private String secret;

    /** Must match exactly on validation — see JwtService's issuer discussion. */
    private String issuer = "orders-api";

    /** Kept short deliberately — see README.md's refresh-token discussion. */
    private long accessTokenTtlMinutes = 15;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public long getAccessTokenTtlMinutes() {
        return accessTokenTtlMinutes;
    }

    public void setAccessTokenTtlMinutes(long accessTokenTtlMinutes) {
        this.accessTokenTtlMinutes = accessTokenTtlMinutes;
    }
}
