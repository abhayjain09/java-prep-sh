# OAuth2 Authorization Code Flow with PKCE (+ OIDC)

This is the flow a real enterprise SPA (this repo's future `angular/` module)
would use to authenticate a user against an external Identity Provider like
OKTA, rather than the self-issued-JWT login this module implements directly
(see [jwt-request-validation-flow.md](jwt-request-validation-flow.md) for
that simpler, single-app flow). **This diagram is explained conceptually with
example steps — there is no reachable OKTA tenant in this sandbox, so nothing
here is a live integration.**

```mermaid
sequenceDiagram
    actor User as Browser (User)
    participant SPA as Angular App
    participant Auth as Auth Server (OKTA)
    participant API as Orders API<br/>(Resource Server)

    Note over SPA: 1. Generate PKCE pair before redirecting:<br/>code_verifier (random string, kept in SPA memory)<br/>code_challenge = BASE64URL(SHA256(code_verifier))

    User->>SPA: clicks "Log in"
    SPA->>User: redirect browser to OKTA's /authorize endpoint<br/>with client_id, redirect_uri, scope=openid profile email,<br/>response_type=code, code_challenge, code_challenge_method=S256, state

    User->>Auth: GET /authorize?... (browser navigates)
    Auth->>User: renders OKTA's own login page (SPA never sees the password)
    User->>Auth: submits credentials (+ MFA challenge if enrolled)
    Auth->>Auth: authenticates user, generates single-use authorization code
    Auth->>User: redirect browser back to redirect_uri?code=...&state=...

    User->>SPA: browser lands back on SPA's callback route
    SPA->>SPA: verify returned state matches the one it generated<br/>(CSRF protection for the OAuth flow itself)

    Note over SPA,Auth: 2. Token exchange happens SPA-backend-to-Auth-server,<br/>not via a browser redirect - the authorization code<br/>and code_verifier never touch the browser's address bar again

    SPA->>Auth: POST /token<br/>grant_type=authorization_code, code, redirect_uri,<br/>client_id, code_verifier (NOT code_challenge)
    Auth->>Auth: recompute BASE64URL(SHA256(code_verifier))<br/>and compare to the code_challenge stored with this code
    alt code_verifier matches original code_challenge
        Auth-->>SPA: 200 OK { access_token, id_token, refresh_token?, expires_in }
    else mismatch / code already used / code expired
        Auth-->>SPA: 400 invalid_grant
    end

    SPA->>SPA: store tokens (see README.md token-storage discussion);<br/>decode id_token to display "Signed in as ..." (do NOT<br/>use id_token to call APIs - see note below)

    User->>SPA: navigates to "My Orders"
    SPA->>API: GET /api/v1/orders<br/>Authorization: Bearer &lt;access_token&gt;
    API->>API: validate access_token signature via OKTA's JWKS<br/>(spring.security.oauth2.resourceserver.jwt.issuer-uri)
    API-->>SPA: 200 OK [ ...orders ]
```

## Why PKCE, specifically, for a browser SPA

Before PKCE, the "authorization code" flow assumed a confidential client — a
traditional server-side web app that could hold a secret (`client_secret`)
known only to itself, exchanged alongside the code at the token endpoint to
prove "the entity redeeming this code is the same one that started this
flow." A single-page app **cannot** hold a secret confidentially — its entire
JavaScript bundle is downloadable by anyone. Without PKCE, an attacker who
intercepted the authorization code mid-redirect (e.g. via a malicious app
registered to the same custom URL scheme on mobile, or a compromised network)
could redeem it themselves. PKCE fixes this without requiring any shared
secret: the SPA generates a random `code_verifier` it never reveals until the
final token-exchange call, sends only a one-way hash (`code_challenge`) up
front, and the auth server checks that whoever redeems the code can produce
the original pre-image. An attacker holding only the intercepted
authorization code cannot produce that `code_verifier`.

## ID token vs. access token — the single most common OIDC confusion

| | **ID token** | **Access token** |
|---|---|---|
| **Standard** | OpenID Connect (an identity layer *on top of* OAuth2) | OAuth2 (authorization only, no identity concept originally) |
| **Format** | Always a JWT | Often a JWT (this repo's convention), but the OAuth2 spec never actually requires it — it can be an opaque reference string the auth server must be asked to introspect |
| **Audience (`aud`)** | The **client application** (the SPA itself) | The **resource server / API** the token is meant to be presented to |
| **Purpose** | Proves *who authenticated* and *when/how* — consumed by the client to render "Signed in as Jane Doe," never sent to an API | Proves *the client is authorized to call an API on the user's behalf* — this is what's sent as `Authorization: Bearer ...` |
| **Contains** | Identity claims: `sub`, `name`, `email`, `auth_time`, ... | Scope/permission-oriented claims — may or may not contain user identity depending on the IdP |

**The mistake this table exists to prevent:** sending the `id_token` to an
API instead of the `access_token`. It usually "works" during development
against poorly-configured resource servers (both are JWTs, both look
plausible), and is wrong: the `id_token`'s audience is the client app, not
the API, and OIDC explicitly does not guarantee an API can safely validate or
trust an ID token the way it can an access token scoped to it. A resource
server that correctly validates `aud` will reject it outright — which is the
*safe* failure mode; a resource server that doesn't check `aud` at all
(README.md's token-validation-mistakes section) might accept it, silently
authorizing API access using a token that was never meant to grant it.

## Where this connects back to this module's code

`SecurityConfig`'s commented "alternative path" and
`application-oauth2-resource-server-example.yml` show the Spring Security
config for the **last two steps** of this diagram only (`API` validating an
externally-issued `access_token`) — the earlier steps (redirect, login page,
PKCE, token exchange) are entirely the SPA's and OKTA's responsibility, not
this backend's. A Spring Boot resource server never participates in, or
needs to know about, the authorization-code/PKCE dance itself.
